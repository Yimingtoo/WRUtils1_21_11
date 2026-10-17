# Phase 3 代码实现解释

本文对应方案 [redstone-unit-testing-plan.md](../redstone-unit-testing-plan.md) 的 **Phase 3（断言深度 + 报告）**。
前序见 [Phase 1](Phase1代码实现解释.md)、[Phase 2](Phase2代码实现解释.md)。

Phase 3 把 Phase 1 的"只能断言方块状态"补成三类断言（方块状态 / 方块实体 NBT / 红石强度），
并把结果从"只在聊天里打印"升级为"聊天 + 日志 + 落盘报告同源"，再加一个 `runall` 批量跑。

## 一、新增与改动

| 文件 | 动作 | 职责 |
|---|---|---|
| `test/model/NbtMatch.java` | 新增 | NBT 期望（SNBT + subset/exact + ignoreKeys） |
| `test/model/RedstoneExpect.java` | 新增 | 红石期望（mode + side + min/max） |
| `test/assertion/NbtSubsetMatcher.java` | 新增 | NBT 递归比较 |
| `test/assertion/RedstoneMatcher.java` | 新增 | 红石强度读取与区间比较 |
| `test/report/TestResult.java` | 新增 | 一次 run 的结果快照 |
| `test/report/ReportWriter.java` | 新增 | JSON + TXT 落盘 |
| `test/model/Expectation.java` | 改 | 由单字段扩为 `(block, strict, nbt, redstone)` |
| `test/assertion/Failure.java` | 改 | 加 `kind` 字段与统一 `describe()` |
| `test/assertion/BlockStateMatcher.java` | 改 | 支持 `strict` |
| `test/assertion/AssertionEngine.java` | 改 | 接入三类断言 |
| `test/io/TestDefinitionLoader.java` | 改 | 解析 `strict` / `nbt` / `nbtMatch` / `nbtIgnore` / `redstone` |
| `test/runner/TestRun.java` | 改 | `place` 支持 NBT；产出 `TestResult`；写报告 |
| `test/WrtTestEngine.java` | 改 | `runall` 串行队列 + 总表 |
| `command/WrtTestCommand.java` | 改 | 加 `/wrutils test runall [filter]` |

## 二、Expectation 的三个条件

```java
record Expectation(String block, boolean strict, NbtMatch nbt, RedstoneExpect redstone)
```

三个字段**互相独立**：可以只写一个，也可以同时写。`AssertionEngine.check` 逐个检查，
每个条件各自产生 `Failure`，所以一条 `assert` 步骤**可能产出多条**失败（如方块对了但 NBT 不对）。

`strict` 只影响 `block`：false（默认）只比作者写了的属性；true 比期望完整属性集。

## 三、NbtSubsetMatcher

- 期望值经 `StringNbtReader.readCompound(snbt)` 解析；实际值取
  `blockEntity.createNbt(world.getRegistryManager())`（**不是** `createComponentlessNbt`，后者丢 `id`）。
- **数值按 `doubleValue()` 比较**：避免期望写 `1`、实际是 `1b` 时因类型不同而误报。
- **列表要求长度相等 + 逐元素递归**（而非"包含即可"）。这是刻意的严格化，**语义对齐原版
  `/execute if data`**（`NbtHelper.matches`）：多出一个物品会被判失败，不会假 PASS。
  物品栏（箱子/漏斗的 `Items`）按槽位顺序写出，顺序稳定。
- `ignoreKeys` 默认剔除 `x/y/z/id/components`，且**只作用于顶层**。
  - **只作用于顶层**很关键：否则会把 `Items[...]` 里物品自身的 `id`（如 `minecraft:redstone`）
    和物品的 `components` 一并忽略掉，物品断言就形同虚设。原版的忽略表同样只在顶层生效。
  - `components` 入默认忽略是因为 1.21 起方块实体自身也带一个 `components` 容器（常见为 `{}`），
    不忽略会让 `exact` 模式对任何方块实体都直接失败。要断言它就用 `nbtIgnore` 显式覆盖。
  - `exact` 额外要求实际不含（未被忽略的）期望之外的键。

## 四、RedstoneMatcher

按 `mode` 选读法（均由 `javap` 核实存在于 1.21.11）：

| mode | 调用 |
|---|---|
| `received` | `world.getReceivedRedstonePower(pos)` |
| `strong` | `world.getReceivedStrongRedstonePower(pos)` |
| `emitted` | `world.getEmittedRedstonePower(pos, side)` |

比较 `min ≤ v ≤ max`（单值就写 `min == max`）。
**语义边界**：读红石粉自身的 `power` 属性属于 **blockstate** 断言（`redstone_wire[power=15]`），
与红石强度断言语义不同——前者是方块属性，后者是"该位置从邻居收到多少强度"。

## 五、place 步骤现在支持 NBT（Phase 3 新增）

Phase 1/2 的 `placeBlock` 只取 `BlockArgumentParser.block(...).blockState()`，**丢弃方块串里的 NBT**，
于是"箱子里有物品"这种前置条件根本建立不起来，箱子/漏斗的 NBT 断言无从验证。

现在 `placeBlock` 改成：

1. `BlockArgumentParser.block(blocks, str, true)`（第三参 `true` = 允许 NBT）。
2. `world.setBlockState(pos, parsed.blockState(), Block.NOTIFY_ALL)`。
3. 若 `parsed.nbt()` 非空：取 `world.getBlockEntity(pos)`，用
   `NbtReadView.create(ErrorReporter.Logging, world.getRegistryManager(), nbt)` 桥成 `ReadView`
   再 `blockEntity.read(...)`。

> **实测踩坑（2026-10-13）**：方块串**不带** `{...}` 时 `BlockArgumentParser.block(...).nbt()` 返回
> **`null`**（不是空 compound）。首版没做 null 检查，任何无 NBT 的 `place`（石头、拉杆、中继器…）
> 都会在 tick 0 抛 NPE 并 ABORTED。已修：`if (nbt == null || nbt.isEmpty()) return;`。

**关键版本事实**：1.21.11 的 `BlockEntity.read` 收的是 `ReadView` 而非 `NbtCompound`——
所以必须经 `NbtReadView.create` 这一层。`ErrorReporter` 用 `Logging`（而非 `EMPTY`），
让坏 NBT 写进日志而不是静默丢弃。

## 六、报告：三处同源

`Failure.describe(testId, dimension)` 是**唯一**的失败文本来源，
聊天（`sendFeedback`）、日志（`WrtLog`）、报告 TXT 都用它，保证三处一字不差。

```
[FAIL] chest_nbt_subset step#1 tick=1
  at   minecraft:overworld 100 64 200
  kind nbt
  exp  {Items:[...]}
  act  {Items:[...], id:"minecraft:chest", x:100, y:64, z:200}
  why  /Items[0]/count: expected 5, got 3
```

`ReportWriter.write` 落盘一对同名文件到 `<存档>/wrutils1_21_11/reports/`：
`<时间戳>-<id>.json`（结构化，含逐条失败）与 `.txt`（人读）。时间戳带毫秒，避免同秒覆盖。
`ReportWriter.writeQuietly` 供调用方使用：落盘失败只告警、不影响 run 收尾。

两个展示/覆盖细节（2026-10-13 修正）：

- **失败信息里的 `exp` 对 blockstate 显示"作者写的那串"**（`expect.block()`），而非
  `stringifyBlockState(parsed.blockState())`。后者会把未写的属性补成默认值（如 `lever` 的 `face=wall`），
  误导读者以为期望就是这样。
- **启动失败也要落盘**：`runall` 里某测试启动失败（越界 / gamerule 名错）时，引擎会为它写一条
  `state=ABORTED`、`kind=startup` 的报告——否则磁盘上会凭空少一条，失败记录不完整。
- **非步骤类失败不显示 `step#`**：`stepIndex < 0`（启动失败）时行首只留 `[FAIL] <id> tick=N`；
  且 `expected`/`actual` 都为空时省略 `exp`/`act` 两行，避免出现 `step#-1` 与两个空行。

## 七、runall 的串行队列

引擎仍**同一时刻只允许一个 run**。`runall` 是一次性把一批定义塞进队列，
每次 run 结束后在 `onEndWorldTick` 里自动推进下一个，全部完成后打印总表：

```
[WRT] runall -- 5 tests
  PASSED  redstone_dust_power  (0 failed)
  FAILED  chest_nbt_subset  (1 failed)
  ...
[WRT] runall: 4/5 passed
```

- **单跑**（`test run`）启动失败时异常**照旧抛给命令层**，玩家看到可读错误（Phase 2 行为）。
- **批跑**启动失败（如某测试的 gamerule 名字写错、或 `place/use` 越界）**不能打断整批**：登记一条 `ABORTED` 结果继续下一个。
  这条差异是刻意的——两条路径的失败语义不同。
- **推进必须能跨过启动失败**（2026-10-13 修复）：`startNext()` 必须是**循环**——启动失败后 `currentRun` 为空，
  而队列推进原来只挂在 `onEndWorldTick`（`currentRun != null` 才动）。首版因此**在第一个启动失败的测试处卡死**，
  后面的测试永远轮不到。踩坑记录见验证手册。

## 八、关键不变量（在 Phase 2 的 9 条之上追加）

10. **一条 assert 可以产出多条 Failure**：三个条件各自独立检查，不要假设"一步一失败"。
11. **NBT 列表是"长度相等 + 逐元素"**，不是"包含"——多出元素会失败。
12. **失败文本只有 `Failure.describe` 一个来源**，聊天/日志/报告文件必须一致。
13. **`runall` 不放松互斥**：队列里的 run 仍是一个接一个；批跑期间 `startRun` 会被拒绝。
14. **单跑启动失败抛给命令层，批跑启动失败降级为结果**——两条路径语义不同，不要统一。
