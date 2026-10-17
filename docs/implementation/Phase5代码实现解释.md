# Phase 5 代码实现解释（游戏内测试编辑器）

配套：[Phase 5 验证手册](Phase5验证手册.md)、[方案总纲](../redstone-unit-testing-plan.md)。
**状态**：5.1–5.5 全部落地并游戏内实测通过（2026-10-14）。多人服务器支持已排入 Phase 6。

---

## 一、逐文件职责

### main / 服务端通用

| 文件 | 职责 |
|---|---|
| `test/io/TestDefinitionWriter.java`（**新增**） | 模型 → JSON。`SCHEMA_VERSION = 3`；`origin` 写绝对值、其余坐标写**相对偏移**；`area == null` 整段省略；`input` **恒写数组**（至少 `[]`）；落盘用 `.tmp` + `ATOMIC_MOVE` **原子写**，防服务端线程读到半截文件 |
| `test/io/TestDefinitionLoader.java` | 抽出 `parse(json, sourceName)`（`load(Path)` 变薄壳）；`schemaVersion` 是硬闸；`readStored(el, origin)` 把偏移加回 `origin`；`readInputs` 读**数组**，写成对象直接报错 |
| `test/model/AreaSpec.java` | `inputs: List<Region>`（空 = 不允许任何写入）+ `bounds()`（包围盒，给强制区块/清实体用）+ `allows(pos)`（任一所含） |
| `test/WrtTestEngine.java` | 新增 `isRunning()`（编辑器互斥；`currentRun` 加 `volatile`）与 `stop()`（中止 run + 清批跑队列 + 停录制探测） |
| `test/runner/TestRun.java` | 新增 `abort()`（按 `ABORTED` 走正常的 `finish` 收尾）；`validateWritable` 改"任一可写区包含"；`chunkRegion` 改用 `AreaSpec.bounds()` |
| `test/runner/WorldPrep.java` | `clearInput` 改为**逐区域**清理；其余（强制区块/清实体/游戏规则）走 `chunkRegion`，未动 |
| `command/WrtTestCommand.java` | 新增 `/wrutils test roundtrip [id]`（往返保真自检）与 `/wrutils test stop`；`test new` 的骨架改走 writer |

### client / GUI（`client/editor/`）

| 文件 | 职责 |
|---|---|
| `EditorSession.java` | **客户端工作副本**：内存里一律**绝对坐标**，保存时才交给 writer 换算。另存"拾取会话"（跨 Screen 必须放这里）、可写区的增删与改角、整体平移。做成**进程级单例** |
| `TestEditorScreen.java` | 测试列表屏：左列自绘列表（可滚轮）+ 右列只读详情 + 一排按钮 `新建/编辑/运行/刷新/关闭` |
| `TestEditScreen.java` | **整窗**编辑器：元数据、条目列表（setup 与 steps 合一，前半 setup 后半 step）、选中项表单（`tick`/坐标/方块串）、两排按钮 |
| `TestAreaScreen.java`（**新增**） | 可写区屏：区域列表、`＋区域`/`删除`/`拾取角1`/`拾取角2`、手改 `min`/`max` |
| `StepEditor.java` | 步骤是不可变 record，改动靠重建——把"重建"集中一处，避免界面里到处写 type switch |
| `BlockStringValidator.java` | 复用 `/setblock` 的解析器（`BlockArgumentParser.block(..., allowNbt=true)`）做方块串校验；另含坐标串与整数校验 |
| `client/Wrutils1_21_11Client.java` | 键位 **K**（开编辑器）、**P**（拾取方块）；运行中拒开并附可点击的「[停止测试]」；拾取后按类型回编辑屏或可写区屏 |

---

## 二、数据流走查

```
按 K ──► TestEditorScreen.init
          └─ TestPaths.testsDir(client.getServer()) → Files.list(*.json)   ← 直接读存档目录
点「编辑」──► TestDefinitionLoader.load(file)                              ← 客户端可调（无服务端依赖）
          └─ EditorSession.load(...)                                        ← 绝对坐标进内存
编辑控件 ──► EditorSession 的可变状态（含拾取会话）
点「保存」──► validateAll() → widenInputToFit()（仅单区域） → TestDefinitionWriter.write(原子写)
点「运行」──► 同上先存盘 → sendChatCommand("wrutils test run <id>") → 关屏
          └─ 服务端 WrtTestCommand.run → load(现读磁盘) → WrtTestEngine.startRun
```

**关键点：服务端 IO 一行未改**。引擎每次 run 都现读磁盘、不缓存定义，所以**编辑器保存后不需要通知任何人**——
这正是 Phase 5 能"不引入任何网络包"的根本原因。

---

## 三、关键不变量

1. **IO 层是坐标语义的唯一边界**。模型（`TestDefinition`/`AreaSpec`/`Region`/`Step`）与引擎全程**绝对坐标**；
   JSON 里的 `origin` 恒为绝对值、其余为偏移。改动只发生在 `TestDefinitionLoader.readStored` 与
   `TestDefinitionWriter.relArray` 两处。
2. **`schemaVersion` 是硬闸**。缺字段或版本号不认识 → 直接拒绝。
   两种语义/形状无法从内容上区分，猜错就是静默跑错——**宁可炸**。
3. **可写区在自动模式下只增不减**。保存时**只有恰好一个**区域才自动扩到覆盖全部条目；
   建了第二个区域就完全交给用户。这是为了让"多区域"与"省事的自动扩写"共存。
4. **编辑器不持有运行态**。互斥靠**同 JVM 直读** `WrtTestEngine.isRunning()`（单人下客户端与服务端同一单例）。
5. **拾取会话必须跨 Screen**：选点要"关屏 → 看世界 → 回屏"，只有进程级状态接得住；
   因此它放在 `EditorSession`，并在 `load()`/`reset()` 与"打开列表"时清掉，防误填。
6. **`shouldPause() == true` ⇒ 运行前必须关屏**：否则世界暂停、`END_WORLD_TICK` 不触发，run 永远等不到 tick。
   「运行」按钮的内部顺序就是"先 `close()` 再发命令"。
7. **禁用输入框要连 `active` 一起关**：`ClickableWidget.mouseClicked` 只查 `isInteractable()`，
   光 `setEditable(false)` 点击照样被处理（表现为"闪一下又变灰"）。
8. **清焦点只能延后一帧，且要排除文本框**：`ParentElement.mouseClicked` 的顺序是
   "先跑按钮回调、后 `setFocused(按钮)`"；而点输入框必须保留焦点。

---

## 四、验证基础设施

仓库**没有测试框架**，所以：

- `scripts/typecheck.sh` 是唯一的自动编译验证（不校验资源/refmap/运行时行为）；
- `/wrutils test roundtrip` 是把"模型与 JSON 同构"这条不变量变成**可执行证据**的唯一手段；
- 离线等价做法：用 `typecheck` 编出的类，对 `docs/examples/` 或存档目录跑
  `load → toJson → parse → equals`（Phase 5 期间多次用它提前发现问题，含本阶段的两次 schema 迁移）。

---

## 五、与方案的偏离 / 有意不做

| 项 | 说明 |
|---|---|
| 屏的划分 | 原计划 `TestListScreen` + `TestEditScreen`；实际中间一度合并为一屏，因默认界面尺寸下右半屏只有约 158px 放不下，最终定为"列表屏 + 整窗编辑屏"（新增了第三块「可写区」屏，方案里没有） |
| 列表控件 | **不用** `EntryListWidget` 系：1.21.11 那套要实现的抽象方法多且变动频繁，改为自绘 + 手动命中检测，只依赖 `DrawContext`/`Click` |
| `保存并运行` → `运行` | 简化为一个按钮（= 先校验保存、再跑）。不再提供"跑磁盘上的旧版本"，那只会让人困惑 |
| setup 与 step 分开显示 | **用户明确"暂时不实现"**，编辑屏里两段同列在一个列表中 |
| `use` 的 `side`/`hand` | 不可编辑，用默认 `UP`/`MAIN_HAND`（与现有示例一致） |
| `expect` 的 `strict`/`nbt`/`redstone` | 编辑 `block` 时**保留**它们，但 5.5 内不提供编辑入口 |
| 多人服务器 | 排入 **Phase 6**（与木斧选区共用自定义 payload 基础设施） |

## 六、已知未闭环

- `cleanup` 的 `none`/`snapshot` 仍未实现（schema 已预留，选用即抛错）。
- `withinTicks`（1 tick 脉冲类断言）仍未实现。
- 按钮元件（方案风险 #1 的剩余项）仍未单独验证。
