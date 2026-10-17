# Phase 2 代码实现解释

> **⚠ 2026-10-13 起本文的隔离语义已废弃**：Phase 2 的 `isolate.radius/clearArea`（清空 `origin±radius`）
> 是**破坏性**的，已换成 `area.input`（可写区）+ `area.cleanup` 的**非破坏性**方案；
> 键名 `isolate` → `area`、类 `IsolationSpec` → `AreaSpec`。下文出现的旧名均属下文的**历史记录部分**。
> 下文关于 `clearArea`/`radius` 的描述**仅作历史记录**；现行语义见
> [非破坏性可写区调整.md](非破坏性可写区调整.md) 与 [方案总纲](../redstone-unit-testing-plan.md) 的「测试区域与副作用」。

本文对应方案 [redstone-unit-testing-plan.md](../redstone-unit-testing-plan.md) 的 **Phase 2（数据模型补全 + 世界隔离）**。
Phase 1 的逐包说明见 [Phase1代码实现解释.md](Phase1代码实现解释.md)。

Phase 2 加了两块东西：**世界隔离**（让测试可重复）和 **选区 / 命令**（让测试可创建）。隔离是核心，命令是配套——没有隔离，同一测试跑两次结果可能不同；没有选区，`test new` 无从下笔。

## 一、新增与改动

| 文件 | 动作 | 职责 |
|---|---|---|
| `test/model/IsolationSpec.java` | 新增 | 隔离规格（radius / clear* / forceChunks / gameRules） |
| `test/runner/WorldPrep.java` | 新增 | 隔离的执行者与还原者 |
| `command/SelectionManager.java` | 新增 | 按玩家 UUID 存选区 |
| `command/WrtSelectionCommand.java` | 新增 | `/wrutils sel pos1\|pos2\|clear\|info` |
| `test/model/TestDefinition.java` | 改 | 加 `isolate` 字段 |
| `test/io/TestDefinitionLoader.java` | 改 | 解析 `isolate`（带默认值，缺省为 `null`） |
| `test/io/TestPaths.java` | 改 | 加 `reportsDir()` |
| `test/runner/TestRun.java` | 改 | 接入 WorldPrep、异常兜底、`forceRestore()` |
| `test/WrtTestEngine.java` | 改 | 挂 `SERVER_STOPPING`，停机前强制还原 |
| `command/WrtTestCommand.java` | 改 | 扩到 `list` / `show` / `new` / `reload` |

## 二、WorldPrep：隔离与还原

### prepare() 的四步，顺序固定

```
clearArea → forceChunks → clearEntities → applyGameRules
```

1. **`clearArea`** —— `origin ± radius` 全部设为空气，flag 用 **`Block.NOTIFY_ALL`** 而非 `FORCE_STATE`。
   `FORCE_STATE` 会跳过邻居更新，可能让残留的红石元件停留在不一致状态；这里要的是"这块区域从来就是空的"的效果。（代价是清一大片区域会触发大量方块更新，Phase 2 可接受。）
2. **`forceChunks`** —— 对覆盖区域的每个 `ChunkPos` 调 `world.setChunkForced(cx, cz, true)`，**成功的**记进 `forcedChunks` 以便还原。
   必要性有两条：① 测试区可以离玩家很远，不强制加载就不 tick；② Phase 4 的 ServerReplay 区块录制开始时会加载区块，之后不再强制加载。
   区块坐标由方块坐标 `>> 4` 算出（对负数也是算术右移，正确），不依赖 `ChunkPos` 的字段名。
3. **`clearEntities`** —— 只清 `ItemEntity` 和 `ExperienceOrbEntity`（区域内）。**不会误伤玩家**：它是静态类型过滤，而 `FakePlayer` 根本不在 `PlayerManager` 里。
4. **`applyGameRules`** —— 见下文"类型转换"。

### restore() 的语义边界

**只还原 `prepare()` 改动的全局量**：gamerule 和强制区块。

**不还原被清掉的方块**——那是测试前的既定环境，事后无从得知原状；清掉就是清掉。所以"测试区"是**破坏性**的：跑测试会永久改变那片区域。这既是代价，也是特性（跑完能看到测试留下的现场）。

`restore()` 是**幂等**的：`savedRules` / `forcedChunks` 处理后立即清空，重复调用无副作用——这是三个兜底点能安全重入的前提。

### 三个兜底点：还原在每条结束路径上都会发生

| 触发点 | 谁负责 |
|---|---|
| `prepare()` 中途抛异常 | `WorldPrep.prepare()` 自己 catch → 回滚 → 重新抛出 |
| 执行步骤时抛异常 | `TestRun.tick()` 的 try/catch → `finish(ABORTED)` → `forceRestore()` |
| 正常结束 / 超时 | `TestRun.finish()` → `forceRestore()` → `report()` |
| 服务器停机 | `WrtTestEngine.onServerStopping()` → `currentRun.forceRestore()` |

最后一条是关键：gamerule 是**按世界存储**的全局量，测试期间会改到玩家所在世界的规则。若在那个窗口里退出存档而不还原，改动会被写进存档文件。`SERVER_STOPPING` 在世界保存**之前**触发，是最后一道闸。

### gamerule 的类型转换

JSON 只能给出 `Boolean` 或数字，而 `GameRules.setValue` 要求 `GameRule<T>` 的 T。桥接靠 `GameRule.getValueClass()`：

```java
rules.setValue((GameRule<Object>) rule, convert(rule.getValueClass(), value), server);
```

- 找规则：`rules.streamRules()` 按 `getId().getPath()` 匹配名字（如 `"random_tick_speed"`）。
- 转换：`Integer.class` → 取 `intValue()`；`Boolean.class` → 取布尔值。
- 那个 unchecked cast 是安全的，因为紧接着的 `convert` 严格按 `getValueClass()` 产出对应类型。

**这是 Phase 2 唯一无法离线验证的部分**——它需要 `GameRules` 的运行时实例。离线自检只覆盖了 JSON 解析层（`IsolationSpec` 的字段与类型）。游戏内必须专门确认 gamerule 真的被设上、又真的被还原。

### 时机：prep 在构造 TestRun 时就做

`TestRun` 的构造函数里 `prepare()`，而构造函数由命令处理器在服务端线程调用。所以**命令一敲下去，测试区立刻被清空**，玩家会当场看见。之后 tick 0 的 `setup` 才开始放方块。

若 `isolate` 为 `null`（JSON 里没写这个字段），整个 WorldPrep 不创建，退化成 Phase 1 的行为——这是向后兼容的关键，老测试文件不用改。

## 三、选区

### SelectionManager

`Map<UUID, BlockPos> × 2`（pos1 / pos2），**按玩家隔离**。单人场景下用全局一份也够，但按玩家存不会在多人时互相覆盖。

### WrtSelectionCommand

- `/wrutils sel pos1|pos2` **省略坐标**时用 `player.raycast(200, 0, false)` 取视线命中的方块——这是"指向哪就选哪"的便利路径。
- 判定命中用 `hit instanceof BlockHitResult`，**不查 `HitResult.Type` 枚举常量**（少依赖一个可能与版本绑定的名字）。
- `/wrutils sel info` 显示 min/max 与体积（`Math.min/max` 六个分量手算，不依赖 `BlockPos.min/max` 这类辅助方法）。

选区是**内存态**，不落盘：它是构造测试时的临时输入，不是测试数据本身。

## 四、命令层

| 命令 | 做什么 |
|---|---|
| `test list [filter]` | 扫 `tests/` 目录列 `.json` 文件名（去掉后缀），可选子串过滤 |
| `test show <id>` | 加载并打印：元信息 + `isolate` + 数量 + 逐条步骤摘要 |
| `test new <id> [desc]` | **从选区**生成骨架 JSON |
| `test run <id>` | Phase 1 原有 |
| `test reload` | 重新扫描目录并报告数量 |

`test new` 的两个细节：

- **origin 取 `pos1`，radius 取 `pos1` 到 `pos2` 在各轴上的最大偏移**——所以骨架的隔离范围正好包住选区。
- 生成用的是**手工拼 `JsonObject` + Gson pretty printing**，不是完整 serializer。完整 serializer（`TestDefinitionWriter`，要求 load→save→load 往返保真）是 **Phase 5** 的工作。这里只要产出一个格式正确的可编辑文件即可。

`test reload` 目前是**名义上的**：引擎每次 `run` 都从磁盘现读，没有缓存可言。它的价值是给玩家一个"我改完文件了"的确认反馈。等 Phase 5 有了 GUI 内的编辑缓存，它才会有真实语义。

## 五、完整走查：一次带隔离的 run

```
/wrutils test run lever_toggle
  │
  ├─ 读文件 → TestDefinition（isolate 非 null）
  │
  ├─ WrtTestEngine.startRun()
  │     └─ new TestRun(...)
  │           ├─ new WorldPrep(world, origin, spec)
  │           ├─ prep.prepare()
  │           │     ├─ 清空 (100,64,200)±2 —— 立刻可见
  │           │     ├─ 强制加载覆盖到的区块
  │           │     ├─ 清掉区域内掉落物与经验球
  │           │     └─ random_tick_speed=0、spawn_mobs=false（原值已存）
  │           └─ new TestScheduler(flatten(...))
  │
  ├─ tick 0 → 放 stone、放 lever
  ├─ tick 2 → 假玩家点击
  ├─ tick 3 → 断言
  │
  └─ finish(DONE)
        ├─ forceRestore()
        │     ├─ 还原 gamerule
        │     └─ 解除强制区块
        └─ report()
```

注意结束时**没有**清空测试区——拉杆、石头、以及测试产生的任何东西都会留在原地。

## 六、关键不变量

在 Phase 1 的 6 条之上追加：

7. **`restore()` 只碰全局量**（gamerule、强制区块），不试图还原方块——并保持幂等，允许多条路径重复调用。
8. **还原必须覆盖全部结束路径**：prepare 失败、tick 抛异常、正常结束、服务器停机，四条都不能漏。
9. **`isolate` 为 `null` 时引擎不创建 WorldPrep**，保持 Phase 1 行为（向后兼容）。
