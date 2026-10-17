# Phase 1 代码实现解释

本文对应方案 [redstone-unit-testing-plan.md](../redstone-unit-testing-plan.md) 的 **Phase 1（最小可运行闭环 / tracer bullet）**，说明落地后的包结构与职责分工。

Phase 1 共 7 个包，依赖**单向**：上层依赖下层，`model` 在最底层，不依赖任何人。

## 一、包依赖方向

```
command ──► test/WrtTestEngine ──► runner ──┬─► model
                                            ├─► exec ──────► model
                                            ├─► assertion ─► model
                                            └─► io ───────► model
```

- `model` 是根：纯数据，零 MC 行为。
- `exec` / `assertion` / `io` / `runner` 只向下依赖 `model`。
- 没有任何包反向依赖 `command`（命令层只负责入口，不含测试逻辑）。

## 二、全景：一次运行的数据流

```
玩家: /wrutils test run lever_toggle
  │
  ├─ command/WrtTestCommand        找文件、解析、拿世界、启动
  │     ├─ test/io/TestPaths            算出 <存档>/wrutils1_21_11/tests/lever_toggle.json
  │     └─ test/io/TestDefinitionLoader .json → 内存对象
  │           └─ test/model/TestDefinition (setup + steps)   ← 纯数据,零逻辑
  │
  ├─ test/WrtTestEngine.startRun()  并发闸门 + 创建 TestRun
  │
  └─ 此后每个世界 tick 自动驱动:
     ServerTickEvents.END_WORLD_TICK
       └─ test/WrtTestEngine.onEndWorldTick()
            └─ test/runner/TestRun.tick()
                 ├─ runner/TestScheduler.due(相对tick)   → 本 tick 该跑哪几步
                 ├─ PlaceStep  → world.setBlockState
                 ├─ UseStep    → test/exec/BlockInteractor.use()      ← 假玩家点击
                 └─ AssertStep → test/assertion/AssertionEngine.check()
                                   └─ BlockStateMatcher.mismatch()
                                        └─ Failure（失败详情）
                 └─ 跑完/超时 → TestRun.report() → 聊天 + 日志
```

## 三、逐包职责

### `test/model/` —— 纯数据，零行为

JSON 的 Java 映射。没有一行 MC 操作，没有一行判断逻辑（除了 sealed 的穷尽性）。

| 类 | 说明 |
|---|---|
| `Step`（sealed interface） | 三种步骤唯一的共同点是 `tick()`。用 sealed 是为了让下游 `instanceof` 分派能被编译器检查穷尽性 |
| `PlaceStep(tick, pos, block)` | 放方块（`steps` 里的 `place` 动作） |
| `SetupBlock(pos, block)` | 初始化放方块（`setup` 列表项，**不带 tick**） |
| `UseStep(tick, pos, side, hand)` | 模拟玩家点击 |
| `AssertStep(tick, pos, expect)` | 断言 |
| `Expectation(block)` | Phase 1 只有方块串一个字段 |
| `TestDefinition(id, description, dimension, origin, timeoutTicks, setup, steps)` | 一次测试的完整描述；`setup` 与 `steps` **分开保留** |

**它是根**：其他所有包都依赖它，它不依赖任何包。改 JSON schema 只需要动 `model` + `io` 两处。

### `test/io/` —— 磁盘 → 内存

唯一知道 JSON 长什么样的地方。模型层完全不知道 JSON 的存在。

- `TestPaths` —— 路径规则（存档目录下 `wrutils1_21_11/tests/<id>.json`），集中一处；Phase 2 加 reports / config 时继续放这里。
- `TestDefinitionLoader` —— 读文件、手写 `action` 分派（`place` / `use` / `assert`）。`setup` 与 `steps` **原样分别装入模型**，此处不合并。

模型与 JSON **同构**（两个列表一一对应），这是为游戏内 GUI 编辑器准备的：GUI 读写的就是这两个列表，往返无损。若在解析时就把 `setup` 揉进 `steps`，事后就无法区分某个 tick 0 的放方块是初始化还是用户手写的步骤——靠 `tick == 0` 猜不可靠。

脱糖只发生在一处：`TestRun.flatten()`，在调度前把 `setup` 变成 tick 0 的 `PlaceStep` 并与 `steps` 合并。引擎内部仍然只有一条时间轴。

### `test/exec/` —— 与方块交互

- `BlockInteractor.use()` —— 照搬 GameTest 的 `useBlock` 三段式（`onUseWithItem` → 条件性 `onUse` → `useOnBlock`）。

```java
ActionResult result = state.onUseWithItem(stack, world, player, hand, hit);
if (result.isAccepted()) return result;
if (result instanceof ActionResult.PassToDefaultBlockAction) {
    if (state.onUse(world, player, hit).isAccepted()) return result;
}
stack.useOnBlock(new ItemUsageContext(player, hand, hit));
```

这个包只有 30 行，但**它是整个 Phase 1 的技术风险点**。`ActionResult.isAccepted()` 的判断只允许出现在这里一处——方案文档特别强调过，用 `== ActionResult.SUCCESS` 会静默错判。

### `test/assertion/` —— 判定与记录

| 类 | 职责 |
|---|---|
| `AssertionEngine.check()` | 解析期望串 → 读实际方块状态 → 产出 `List<Failure>`。**失败不抛异常、不中断**，一条 assert 返回 0 或多个 Failure |
| `BlockStateMatcher.mismatch()` | 子集比较（只比作者写了的属性），失败时返回一句人类可读的 `why` |
| `Failure` | 失败详情 record（tick / stepIndex / pos / expected / actual / why） |

期望串走 `BlockArgumentParser.block(...)`，和 `/setblock` **同源解析器**——所以玩家能直接抄 `/setblock` 的写法。

### `test/runner/` —— 时间轴

| 类 | 职责 |
|---|---|
| `TestScheduler` | 构造时把步骤按 `(tick, phase)` 排序，phase 顺序是 **place(0) < use(1) < assert(2)**；`due(相对tick)` 每次弹出到期步骤并推进游标（均摊 O(1)） |
| `TestRun` | 一次运行的全部状态：世界、假玩家、失败列表、计数、相对 tick 计数器。构造时用 `flatten()` 把 `setup` 脱糖为 tick 0 的 `PlaceStep` 并与 `steps` 合并成单一时间轴。`tick()` 是主循环（先查 `shouldTick()`，freeze 时不推进），`report()` 把结果发到聊天和日志 |
| `RunState` | `RUNNING` / `DONE` / `ABORTED` |

同一 tick 内"先 use 后 assert"是靠**排序**实现的，不是靠特判。

时间基准是**自维护的相对 tick 计数器**：`relativeTick` 初值 `-1`，每帧回调查到 `world.getTickManager().shouldTick()` 为真才 `++`。

为什么不用 `world.getTime()`（方案文档早期版本如此）：

- 它是 **gametime**（`WorldProperties.time`），唯一递增点是 `ServerWorld.tickTime()`，而 `tickTime()` 被 `shouldTick()` 门控——freeze 时它确实会停，但语义不够显式。
- 更实际的问题是 **tick 0 会漂**：命令若晚于当 tick 的 `END_WORLD_TICK` 执行，run 从 `relativeTick = 1` 起步，`setup` 与首个 `use` 的间隔被压缩 1 tick。

自维护计数器同时解决这两点，并让 `tick 0` 精确落在首个驱动帧。

### freeze 行为（`/tick freeze`）

`END_WORLD_TICK` 注入的是 `ServerWorld.tick` 的 **TAIL**，而 `MinecraftServer.tickWorlds()` 对 `ServerWorld.tick()` 的调用**不检查 `shouldTick`**（那个检查只门控 GameTest），`ServerWorld.tick` 内也没有 early return。所以 **freeze 期间回调仍然每服务端 tick 触发**，`TestRun.tick()` 会被持续调用——但 `shouldTick()` 为假时直接 return，计数器不增长，于是**空转而不推进**。解冻后从原处续跑。

> 注：本包原名 `run`，因 `.gitignore` 的 `run/` 规则（本意是忽略 loom 生成的 `run/` 目录）会匹配任意层级而误伤，故改名 `runner`。

### `test/WrtTestEngine` —— 单例枢纽

三个职责，都是"全局唯一"才放这儿：

1. `bootstrap()` —— 挂 4 个钩子：`SERVER_STARTED` / `SERVER_STOPPED` / `END_WORLD_TICK` / `CommandRegistrationCallback`。
2. `startRun()` —— **并发闸门**。测试是独占操作，`currentRun != null` 就拒绝。
3. `onEndWorldTick()` —— 只驱动"当前 run 所在的那个世界"（多世界时别的世界 tick 一概不管），跑完就置空。

### `command/WrtTestCommand` —— 唯一的玩家入口

- 注册 `/wrutils test run <id>`，权限 `GAMEMASTERS_CHECK`（单人房主默认满足）。
- 只干"入口该干的事"：查文件存在 → 解析 → 拿执行者所在世界 → 启动 → 把错误翻译成人话发回聊天。**不含任何测试逻辑。**

## 四、完整走查：`lever_toggle`

```
setup 2 条  →  PlaceStep(t=0) ×2   ← 脱糖
steps 2 条  →  UseStep(t=2)、AssertStep(t=3)

TestScheduler 排序后 (tick, phase)：
  t0,p0 PlaceStone#0
  t0,p0 PlaceLever#1
  t2,p1 Use#2
  t3,p2 Assert#3

tick0  → 放石头、放拉杆
tick1  → due(1) 为空，什么都不做
tick2  → 假玩家点拉杆
tick3  → 读实际状态，与 lever[powered=true] 比较
跑完   → RunState.DONE → report() → PASSED / FAILED
```

排序与游标行为已用纯 JVM 程序（`SchedCheck`）离线验证过——这是 javac 查不出、又最容易写错的部分。

## 五、关键不变量

后续 Phase 扩展时必须保持：

1. **`ActionResult.isAccepted()` 判断只出现在 `BlockInteractor` 一处。**
2. **`model` 包零 MC 行为、零依赖。**
3. **时间基准是自维护的相对 tick 计数器**；不以 `world.getTime()`（gametime）或 `server.getTicks()`（freeze 时仍在涨）为基准，且每帧必须先查 `shouldTick()`。
4. **同一 tick 内执行顺序由 `TestScheduler` 的 phase 排序决定**，执行器不做特判。
5. **断言失败不中断**，继续跑完以一次暴露全部失败点。
6. **模型与 JSON 保持同构**（`setup` / `steps` 不合并），脱糖只发生在 `TestRun.flatten()` 一处——这是游戏内 GUI 编辑器能做到无损往返的前提。
