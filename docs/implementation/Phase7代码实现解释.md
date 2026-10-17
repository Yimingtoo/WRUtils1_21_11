# Phase 7 代码实现解释（能力补全）

配套：[Phase 7 验证手册](Phase7验证手册.md)、[方案总纲](../redstone-unit-testing-plan.md)。

**状态**：7.1 / 7.2 / 7.3 全部落地；手册 §1–§27 通过（§16 快照体积上限用户决定暂不测试）。
Phase 7 已完成，没有新增未闭环功能。

---

## 一、逐文件职责

### 7.1 宽窗口断言（`withinTicks`）

| 文件 | 职责 |
|---|---|
| `test/model/AssertStep.java` | record 增加 `long withinTicks`；0 = 旧语义（只在该 tick 采样一次），>0 = 窗口内任一 tick 满足即通过 |
| `test/io/TestDefinitionLoader.java` | `readWithinTicks`：键缺省为 0；**负数直接拒绝**（不悄悄当成 0） |
| `test/io/TestDefinitionWriter.java` | **仅 `> 0` 时写出**——旧定义保存后 JSON 形状不变 |
| `test/runner/TestRun.java` | 新增 `pendingAsserts` 与 `checkPendingAsserts()`；收尾/超时条件改为把"还有窗口没结束"也算作未完成 |
| `client/editor/StepEditor.java` | `convert` / `withTick` / `withPos` / `withMainField` 四个重建点全部带上 `withinTicks`；新增 `withWithinTicks` |
| `client/editor/TestEditScreen.java` | 表单改为 `tick / 窗口 / 坐标 / 方块串` 四格；「窗口」仅 assert 可编辑 |
| `client/editor/TestEditorScreen.java`、`command/WrtTestCommand.java` | 步骤展示为 `assert±N` |

### 7.2 清理策略（`cleanup` 的 `none` / `snapshot`）

| 文件 | 职责 |
|---|---|
| `test/model/CleanupMode.java` | 仅更新注释：三种模式都已实现 |
| `test/runner/WorldPrep.java` | 全部实现集中在这里（见下） |

`WorldPrep` 的四块新逻辑：

1. **`prepare()` 顺序**：体积检查 → `forceChunks()` → `applyCleanup()` → 清实体 → gamerule。
2. **`applyCleanup()`**：`NONE` 空操作；`SNAPSHOT` → `captureSnapshot()`；`CLEAR_INPUT` 原样。
3. **`checkSnapshotVolume()`**：可写区总体积上限 `MAX_SNAPSHOT_VOLUME = 1_000_000`；超限直接抛
   `IllegalArgumentException`，建议缩小 `area.input`。放在 `forceChunks` **之前**，超限时不会先加载大片区块。
4. **`captureSnapshot()` / `restoreBlocks()`**：只存**非空气**方块 + 方块实体 NBT；还原分两轮
   （先方块状态，再 NBT）。`restore()` 的总体顺序改为：方块 → gamerule → 取消强制区块。

### 7.3 编辑器入口与默认值

| 文件 | 职责 |
|---|---|
| `client/editor/EditorSession.java` | 新增 `cleanup()` / `setCleanup()`（只换策略，不动区域与其余开关）、`raiseTimeoutToFit()` |
| `client/editor/TestAreaScreen.java` | 底部新增「清理:清空 / 保持 / 快照」循环按钮；6 个按钮均分宽度 |
| `client/editor/TestEditScreen.java` | `prepareForSave()`（扩可写区 + 超时抬高，保存/运行/自动保存三路共用）；`backToList()` 改为**自动保存**；`ConfirmScreen` 拦下失败；`leaving` 防重入 |
| `client/editor/TestEditorScreen.java` | 静态 `lastSelectedId`（K 重开时恢复选中项）；新增 `(initialSelection, notice)` 构造与顶部绿色提示行 |
| `client/editor/BlockStringValidator.java` | 改用统一的 `BlockStringParser` |
| `test/BlockStringParser.java`（**新增**） | 方块串解析的**唯一入口**：解析后检查 `StringReader` 是否读完（尾部空白放行），挡住 `minecraft:stone撒地方` 这类残留 |
| `test/runner/TestRun.java`、`test/assertion/AssertionEngine.java` | `place` 与 `assert.block` 也改走 `BlockStringParser` |

### 示例

| 文件 | 用途 |
|---|---|
| `docs/examples/observer_pulse.json` | 侦测器 1 tick 脉冲；`withinTicks: 3`（窗口 2..5），末尾断言在 tick 10 |
| `docs/examples/cleanup_snapshot_demo.json` | `snapshot` 清理；跑前手动在可写区放带 NBT 的箱子，测试把它换成石头 |

---

## 二、数据流走查

### 1. 窗口断言

```
tick 2  execute(assert, withinTicks=3)
          ├─ asserts++（只记一次）
          └─ pendingAsserts.add(窗口 2..5)     ← 不在 execute 里采样
        步骤循环结束后 checkPendingAsserts()
          └─ 采样 tick 2：不满足 → 等

tick 3/4  同一路径采样；任一 tick 满足 → 移除，不产生失败

tick 5    仍不满足且 relativeTick >= endTick
          └─ 用最后一次采样结果产出失败，why 追加「期望在 tick 2..5 内任一 tick 成立」

收尾：scheduler.isFinished() && pendingAsserts.isEmpty() 才算跑完
超时：等待窗口期间同样受 timeoutTicks 约束
```

**为什么采样放在步骤循环之后**：`place` / `use` 也在同一 tick 执行，窗口的第一次采样必须发生在它们之后。

### 2. `cleanup: snapshot` 生命周期

```
TestRun 构造（服务端线程）
  └─ WorldPrep.prepare()
       ├─ 体积检查（>100 万格 → 报错，不碰世界）
       ├─ forceChunks()                       ← 必须先加载，否则未加载区块读成空气
       ├─ captureSnapshot()                   ← 只拍可写区、只拍非空气 + 方块实体 NBT
       └─ clearEntities / gamerule

测试运行：setup 与 steps 正常修改世界

TestRun.finish() / abort() / 服务器停机
  └─ WorldPrep.restore()
       ├─ restoreBlocks()：把可写区写回快照（快照里没有的位置写空气）
       │    ├─ 第一轮：方块状态（创建对应类型的方块实体）
       │    └─ 第二轮：方块实体 NBT
       ├─ 还原 gamerule
       └─ 取消强制区块
```

快照 = **测试前**的世界，不是 setup 之后：setup 是 tick 0 的步骤，跑完同样会被还原掉。

### 3. 编辑器保存 / 返回

```
点「保存」/「运行」        → validateAll → prepareForSave → WRITE（异步）→ 成功提示 / 失败报错
按 ESC /「返回列表」       → validateAll
                             ├─ 失败 → ConfirmScreen（丢弃离开 / 继续编辑）
                             └─ 成功 → prepareForSave → WRITE
                                  ├─ 成功 → 列表屏（顶部 notice）
                                  └─ 失败 → ConfirmScreen
```

`prepareForSave()`：

```
widenInputToFit()                    ← 单可写区时才自动扩
raiseTimeoutToFit()                  ← max(当前值, 最晚步骤 tick + 100)
                                      窗口断言算到 tick + withinTicks；只升不降
```

---

## 三、关键不变量

1. **窗口是闭区间**：`tick .. tick + withinTicks`，含两端；`withinTicks = 0` 等价于旧行为。
2. **窗口内任一次"全部条件满足"即通过**，且立即停止采样；中间失败不留痕。
3. **`asserts` 只计一次**（步骤开始时），不随采样次数增长。
4. **窗口不能拖住收尾，也不能绕过超时**：收尾条件包含 `pending.isEmpty()`；超时判定覆盖等待窗口。
5. **`cleanup` 只影响可写区**：机器本体、断言位置永远不碰；快照也只覆盖 `area.input`。
6. **快照必须在区块加载之后拍**，且**先拍完再挂到字段上**——中途异常时不能拿半份数据去还原。
7. **还原分两轮**：方块状态 → 方块实体 NBT；顺序反了会写到旧的方块实体上。
8. **`restore()` 在所有路径上幂等**：正常完成、中止、准备失败回滚、服务器停机都走它；还原后清空快照。
9. **`BlockPos.iterate` 的实例是复用的**：入 map 前必须 `toImmutable()`。
10. **超时自动抬高只升不降**：用户显式填的更长时间不会被覆盖。
11. **返回自动保存不会静默丢改动**：校验/保存失败一律先拦下；「丢弃离开」是用户显式选择。
12. **方块串只有一个解析入口**：编辑器校验、`place`、`assert.block` 共用 `BlockStringParser`；
    尾部残留（非空白）一律拒绝，避免"编辑器通过、运行时按前缀生效"。

---

## 四、验证基础设施

- `scripts/typecheck.sh`：唯一的自动编译验证；
- `/wrutils test roundtrip`：`AssertStep` 加字段、`cleanup` 三模式都不改 JSON 形状，往返仍是硬闸；
- 游戏内手册 §1–§27：窗口正常/负向、快照还原（含 NBT 与中止）、cleanup 三模式对照、
  编辑器入口、超时抬高、自动保存与拦截、方块串尾部校验。
- **两条实测时序**（写样例时的依据）：
  - 侦测器脉冲在拉杆点击后 **+2 tick** 才落到红石粉上，所以窗口留到 `withinTicks: 3`；
  - 红石粉的 `received` 读数与 `power` 属性是两条更新路径，样例只断言前者。

---

## 五、与方案的偏离 / 有意不做

| 项 | 说明 |
|---|---|
| 自动超时的语义 | 用户原话是"程序时间 + 100gt"；落地为"最晚步骤（窗口算到末端）+ 100，只升不降" |
| 返回自动保存 | 原方案没有；验收时用户追加，已实现 |
| 方块串尾部校验 | 原方案没有；§26 验收暴露 `BlockArgumentParser` 只解析前缀，新增统一入口修复 |
| 编辑器 cleanup 入口 | 用**一个循环按钮**而不是三个并排按钮；6 个按钮在默认宽度下仍放得下 |
| `forceChunks` / `clearEntities` / `gameRules` | 仍无 GUI 入口，加载时保留原值（Phase 8 候选） |
| `use` 指定手持物品、删除测试文件 | Phase 8 候选，本阶段未动 |
| §16 体积上限 | 代码已实现，用户决定暂不实测 |

---

## 六、已知未闭环

- **§16 未实测**：`MAX_SNAPSHOT_VOLUME` 的分支只有代码审查，没有游戏内证据（用户决定暂不测试）。
- 三条条件全空的 assert 仍被允许（永远通过）；本次未加校验。
- Phase 8 候选清单见[主方案 Phase 7 末尾](../redstone-unit-testing-plan.md)（编辑器其余字段、
  `use` 手持物品、删除入口、`SelectionHud`、PENDING 超时、三处过时文档等）。
