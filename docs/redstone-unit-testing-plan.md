# 红石机器单元测试框架（WRUtils 新功能）

## Context

用户要在 `WRUtils1_21_11`（Fabric 模组，MC 1.21.11，Yarn build.6，Java 21）中新增"红石机器单元测试"能力，做成**面向玩家的游戏内工具**。三块需求：

1. **输入** —— ① 初始化：测试开始前放置方块等事件；② 模拟玩家操作：按时序对指定方块调用方块的 `use` 函数。
2. **过程与结果检测** —— 在指定时序断言某些方块的状态符合预先设置的预期。
3. **失败记录** —— 用回放模组把失败现场录下来。

### 调研结论（推翻了需求里的两个前提）

- **原需求"调用 ReplayMod API"不可行**：官方 ReplayMod 明确声明不提供任何外部 API，且**无法在会话中途开始录制**（只能加入世界时开录），也没有 `/replay start` 之类命令；它是 GPL-3.0，直接链接还有许可证传染。
  → 改用 **ServerReplay**（服务端 Fabric 模组，`3.2.8+1.21.11`，MIT），提供 `/replay start|stop chunks` 命令，产出 ReplayMod/Flashback 兼容的 `.mcpr`。
- **MC 自带 GameTest 框架不能直接当宿主**：`net.minecraft.test.TestContext` 的能力几乎与需求一一对应（`useBlock`、`runAtTick`、`expectBlock`、`expectRedstonePower`…），但它是**无头 TestServer 模型**，与"真实客户端世界里录回放"正交，且 `/test` 命令默认只在开发模式可用。
  → **只借鉴其 API 设计**（尤其是 `useBlock` 的实现序列），引擎自研。
- **Modrinth 上的 `Redstone Unit Testing`(RUT) 不作为依赖**：它虽覆盖输入/断言前半部分，但为 **GPL-2.0-only、1.0.0alpha、疑似闭源（无源码链接）**。Mixin 进闭源 alpha 代码既脆弱又会让本项目被迫 GPL（本项目是 All Rights Reserved）。
  → **只借鉴其交互设计**（木斧左键=输入、右键=断言、按 tick 设输入/断言）。

### 预期产出

玩家能在游戏里用命令+木斧把自己的红石机器圈成测试用例，跑完后在聊天/报告文件里看到逐条断言结果；失败的测试会留下一份可用 ReplayMod/Flashback 打开的回放文件。

## 已确认的决策

| 项 | 决策 |
|---|---|
| 运行环境 | 单人集成服务器 |
| 录制策略 | 每测试全程录制；**通过 → `save=false` 丢弃，失败 → 保留** |
| 检测范围 | 方块状态 BlockState ＋ 方块实体 NBT ＋ 红石强度 |
| 定义方式 | 游戏内命令 ＋ 木斧标记坐标，自动存 JSON |
| 录制后端 | ServerReplay（命令触发，非 API 依赖） |
| 引擎 | 自研，借鉴 GameTest 与 RUT 的设计，不依赖二者 |

## 架构

### 模块划分

**全部测试逻辑放 `src/main`**（服务端侧；单人集成服务器的服务端与客户端同 JVM，命令在服务端线程执行）。`src/client` 只放 Phase 5 的可选渲染/HUD。

```
src/main/java/com/yiming/wrutils1_21_11/
├── Wrutils1_21_11.java              # 改：挂 WrtTestEngine.bootstrap() + LOGGER
├── WrtLog.java                      # 新增：统一 LoggerFactory
├── test/
│   ├── WrtTestEngine.java           # 单例：生命周期 + 当前 run + 对外 API
│   ├── model/                       # TestDefinition / Step(sealed) / PlaceStep / UseStep
│   │                                #   / AssertStep / Expectation / NbtMatch / RedstoneExpect / IsolationSpec
│   ├── io/                          # TestDefinitionLoader(Gson) / TestPaths
│   ├── run/                         # TestScheduler / TestRun / RunState / WorldPrep
│   ├── exec/                        # TestPlayerProvider(FakePlayer) / BlockInteractor
│   ├── assertion/                   # AssertionEngine / BlockStateMatcher / NbtSubsetMatcher
│   │                                #   / RedstoneMatcher / Failure
│   └── report/                      # TestReport / ReportWriter
├── replay/
│   ├── RecordingBackend.java        # 接口（无 ServerReplay 编译期依赖）
│   ├── RecordingSession.java        # 接口 stop(server, keep)
│   ├── ServerReplayBackend.java     # 纯字符串命令实现
│   ├── NoopRecordingBackend.java    # 降级
│   └── RecordingBackends.java       # 探测 + 选择
└── command/                         # WrtTestCommand / WrtSelectionCommand / SelectionManager
```

**Phase 1–4 不引入任何自己的 Mixin**（避免 javac 脚手架无法验证 refmap 的盲区）。

### 测试定义数据模型与持久化

- 定义存 `<世界存档>/wrutils1_21_11/tests/<id>.json`（坐标绑定地图，随存档走）。
- 报告存 `<世界存档>/wrutils1_21_11/reports/<时间戳>-<id>.json` + `.txt`。
- 配置存 `<runDir>/config/wrutils1_21_11.json`。

方块用 **MC 原生描述串**（`BlockArgumentParser.block(...)`），玩家可直接抄 `/setblock` 的写法；NBT 用 SNBT 字符串；红石用带 min/max 的结构化对象。

```json
{
  "id": "repeater_delay_toggle",
  "description": "拉杆 -> 中继器，验证 tick2 点击与 tick10 改延迟",
  "dimension": "minecraft:overworld",
  "origin": [100, 64, 200],
  "timeoutTicks": 200,
  "record": true,
  "isolate": { "radius": 3, "clearArea": true, "clearEntities": true, "forceChunks": true,
               "gameRules": { "random_tick_speed": 0, "do_mob_spawning": false } },
  "setup": [
    { "pos": [100,64,200], "block": "minecraft:repeater[facing=north,delay=1]" },
    { "pos": [100,64,202], "block": "minecraft:lever[face=floor,facing=south,powered=false]" },
    { "pos": [101,64,200], "block": "minecraft:chest[facing=up]" }
  ],
  "steps": [
    { "action": "use", "tick": 2, "pos": [100,64,202], "side": "up", "hand": "main" },
    { "action": "assert", "tick": 3, "pos": [100,64,202],
      "expect": { "block": "minecraft:lever[powered=true]" } },
    { "action": "assert", "tick": 3, "pos": [100,64,201],
      "expect": { "redstone": { "mode": "received", "min": 15, "max": 15 } } },
    { "action": "assert", "tick": 30, "pos": [101,64,200],
      "expect": { "nbt": "{Items:[{Slot:0b,id:\"minecraft:redstone\",count:1}]}", "nbtMatch": "subset" } }
  ]
}
```

- `setup` 是隐式 tick 0、按书写顺序执行的放方块，引擎脱糖为 tick 0 的步骤，保持单一时间轴。
- `steps` 的 `tick` 是**相对 tick**（相对 run 开始时的 `world.getTime()`）。同一 tick 内先执行全部 `use`、再执行全部 `assert`，消除歧义。
- 反序列化用 Gson（已在依赖里）+ 手写 `"action"` 分派（比 `RuntimeTypeAdapterFactory` 报错更可读）。

### 时序调度引擎

- 用 **`ServerTickEvents.END_WORLD_TICK`**（只处理 run 对应的 `ServerWorld`）。它注入 `ServerWorld.tick` 的 TAIL，触发次数**严格等于世界 tick 次数**，所以"第 N tick"语义精确，且断言看到的是该 tick 全部方块/方块实体 tick 完成后的状态。
- **不要用 `END_SERVER_TICK`**：`/tick freeze` 下世界不 tick 而服务端循环仍在跑，会让"第 N tick"与世界 tick 脱钩。
- tick 基准 = `world.getTime()`（`WorldAccess` 的 default 方法）。步骤预排序成 `(tick, phase, index)` 列表，每帧推进指针（O(1) 均摊）。
- **世界隔离**（`WorldPrep`，run 开始前一次性做完，`finally` 还原）：
  1. `clearArea`：`origin ± radius` 全设为空气，flag 用 `Block.NOTIFY_ALL`（保留完整红石更新语义，**不要用 `FORCE_STATE`**）。
  2. `forceChunks`：对覆盖区域每个 `ChunkPos` 调 `world.setChunkForced(cx, cz, true)`（结束时 `false`）。**这一步必须做**——ServerReplay 的区块录制"开始时会加载区块，之后不再强制加载"。
  3. `clearEntities`：清区域内的 `ItemEntity`/`ExperienceOrbEntity`。`FakePlayer` 不在 `PlayerManager` 里，不会被误清。
  4. gamerule：通过 `world.getGameRules().setValue(GameRules.RANDOM_TICK_SPEED, 0, server)` 等（1.21.11 的 `GameRules` 在 `net.minecraft.world.rule` 包，**按世界**存储），记录原值并在结束时还原；`SERVER_STOPPING` 时也要还原。
  5. **不冻结 tick 速率**（`ServerTickManager.setFrozen(true)` 会让世界停 tick，我们的驱动就死了）。
- **状态机**（单例，同一时刻只允许一个 run）：
  `IDLE → PREPARING → RECORDING → RUNNING → FINISHING → DONE|ABORTED → IDLE`
  断言失败**不中断**，继续跑完以一次暴露全部失败点；超时（超过 `timeoutTicks`）或异常 → `ABORTED`，仍走 `FINISHING` 清理（`keep=true`）。

### 模拟玩家点击

**用 Fabric 的 `net.fabricmc.fabric.api.entity.FakePlayer`**（在 `fabric-events-interaction-v0` 4.1.1+3b89ecf63e，本版本已 remap）—— 它继承 `ServerPlayerEntity`，构造时自建 `FakePlayerNetworkHandler`（`send` 空实现 + 假 `ClientConnection`），`tick()`/`increaseStat`/`openHandledScreen` 均已无害化，**不存在 `networkHandler == null` 的 NPE**，无需自己造玩家。

```java
FakePlayer p = FakePlayer.get(serverWorld, new GameProfile(FakePlayer.DEFAULT_UUID, "[WRT] tester"));
```

`BlockInteractor` **照搬官方 `TestContext.useBlock` 的序列**（已反编译核实）：

```java
BlockState state = world.getBlockState(pos);
Hand hand = step.hand();
player.setStackInHand(hand, itemStack);              // 默认 ItemStack.EMPTY
player.refreshPositionAndAngles(pos.getX()+0.5, pos.getY()+1, pos.getZ()+0.5, yaw, pitch); // FakePlayer.tick() 是空的，不会自己动
BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), step.side(), pos, false);
ActionResult r = state.onUseWithItem(player.getStackInHand(hand), world, player, hand, hit);
if (r.isAccepted()) return r;
if (r instanceof ActionResult.PassToDefaultBlockAction) {
    if (state.onUse(world, player, hit).isAccepted()) return r;
}
player.getStackInHand(hand).useOnBlock(new ItemUsageContext(player, hand, hit));
```

**`ActionResult` 在 1.21.11 已从枚举改为接口** —— 判断"是否生效"**只能用 `isAccepted()`**，`== ActionResult.SUCCESS` 会静默错判（`SUCCESS`/`SUCCESS_SERVER`/`CONSUME` 是三个字段不同的 `ActionResult$Success` record）。`PASS` 与 `PASS_TO_DEFAULT_BLOCK_ACTION` 必须区分：只有后者才回退到 `state.onUse(...)`。此判断只允许出现在 `BlockInteractor` 一处。

每次点击把 `ActionResult` 记进 trace（排查"点了没反应"的第一手信息）。

### 断言引擎

统一入口 `AssertionEngine.check(Expectation, ServerWorld, BlockPos, long tick, int stepIndex) -> List<Failure>`，`Expectation` 的三个字段可同时存在。

- **BlockState**：期望串经 `BlockArgumentParser.block(registryWrapper, str, false)` → `BlockResult`（`blockState()` / `properties()` 子集 / `nbt()`）。`strict=false`（默认）只比 block 类型 + 作者写了的属性；`strict=true` 全等。
- **BlockEntity NBT**：实际值取 `be.createNbt(server.getRegistryManager())`（**不要** `createComponentlessNbt`，会丢 `id`）。默认**子集匹配**（期望的 key 必须在实际中存在且递归匹配，数值按数值比较以避免 `0b`/`0` 误报，`ignoreKeys` 默认剔除 `x/y/z/id`）；`mode:"exact"` 才全量。
- **红石强度**：`world.getReceivedRedstonePower(pos)` / `getReceivedStrongRedstonePower(pos)` / `getEmittedRedstonePower(pos, side)`，比较 `min ≤ v ≤ max`。文档需说明：读红石粉的 `power` 属性属于 BlockState 断言（`redstone_wire[power=15]`），与红石强度断言语义不同。

失败信息（聊天 + 日志 + 报告同源）：

```
[FAIL] repeater_delay_toggle  step#5  tick=11
  at   minecraft:overworld 100 64 200
  kind blockstate
  exp  minecraft:repeater[facing=north,delay=2]
  act  minecraft:repeater[facing=north,delay=1]
  why  property delay: expected 2, got 1
```

### 录制后端

接口与实现**只走字符串命令**，因此没有 ServerReplay jar 也能编译、能运行：

```java
interface RecordingBackend { String id(); boolean isAvailable(MinecraftServer); RecordingSession start(MinecraftServer, ServerWorld, RecordingTarget); }
interface RecordingSession { String label(); void stop(MinecraftServer, boolean keep); Optional<Path> producedFile(); }
```

`ServerReplayBackend.start`（命令源已核实可用）：

```java
ServerCommandSource src = server.getCommandSource().withSilent();   // OWNERS = OP 4，正好满足 ServerReplay 要求
server.getCommandManager().parseAndExecute(src,
    "replay start chunks around " + cx + " " + cz + " radius " + r + " in " + dim + " named " + label);
```

- `CommandManager` 在 1.21.11 的方法名是 **`parseAndExecute`**（不是 `executeWithPrefix`）。**不要用 `CommandManager.createSource(...)`**：它构造的 source 里 `MinecraftServer` 为 `null`，`/replay` 内部任何 `source.getServer()` 都会 NPE。
- **必须用 `chunks` 模式，不能用 `players` 模式**：`players` 按在线玩家建录制器，而 `FakePlayer` 不在 `PlayerManager` 里，对它无效。`chunks` 是静态区域录制。
- **"通过则删"直接用命令参数实现**：结束时 `/replay stop chunks named <label> <save?>` —— 通过传 `false`，失败省略（默认 `true`）。这样不需要删文件，绕开落盘竞态。`parseAndExecute` 返回 void 且内部吞异常，所以失败时退回"延迟若干 tick 后 glob `<chunk_recording_path>/<label>/*.mcpr` + `Files.delete` 重试"。
- `label` 从 test id 消毒成 `[A-Za-z0-9_-]`（命令参数不能带空格）。
- **录制半径必须外扩 1 区块**（关键坑）：客户端不渲染录制区域最外圈的区块，所以想看到 `radius=2` 的范围，实际要按 `radius=3` 录。`around` 的 `radius` 传 `desired + 1`。
- **回放可查看性（已确认可行）**：`default_encoding` 默认就是 `"replay_mod"`，产出的 `.mcpr` 可被**客户端 ReplayMod 直接打开**（把文件拷进客户端的 `./replay_recordings/` 即可）。chunk 模式不与玩家视角绑定，回放里是**自由相机**，正好适合观察红石结构。1.1.0+ 还支持**纯服务端预览** `/replay view chunks "<name>" "<时间戳>"`（查看时该玩家被临时视为离线）。注意：ServerReplay 的录制与客户端自录并非 100% 等同（如 boss bar 不录），且与改动网络包的模组可能冲突（有 `ignore_custom_payloads` 配置可关）。
- 已确认：配置在 `./config/server-replay/config.json`，`chunk_recording_path` 默认 `./recordings/chunks`，**`chunk_recording_strategy` 默认 `"always"`**（不需要真实玩家在场也会录；`chunk_contains_player` 只对 Flashback 格式生效）。
- 降级：探测失败（`FabricLoader.isModLoaded("server-replay")` 且 dispatcher 有 `replay` 根节点，二者都查）→ `NoopRecordingBackend`，测试照常跑、报告照常出，只 warn 一次。`fabric.mod.json` 里把 server-replay 写成 **`recommends`** 而非 `depends`（它还需要 Fabric Language Kotlin）。

**强烈建议附带 `"trace": true`**：后端无关的"穷人版回放"——在 ROI 内每 tick 记录发生变化的方块 `pos/oldState/newState` 写进报告 JSON。这是 ServerReplay 缺席时唯一能保住验收价值的东西。

### 命令

全部 `CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK)`（OP 2，单人房主默认满足）。参数用原版 argument type 以拿到 tab 补全（`BlockPosArgumentType`、`BlockStateArgumentType`、`IdentifierArgumentType`…）。

```
/wrutils test list [filter]
/wrutils test show <id>
/wrutils test new <id> [name]          # 从当前选区生成骨架 JSON
/wrutils test run <id> [--record|--no-record]
/wrutils test runall [filter]
/wrutils test delete <id> | reload
/wrutils sel pos1|pos2 [<pos>]         # 省略坐标时取玩家视线命中的方块
/wrutils sel clear | info
/wrutils recording status | test       # test = 立刻起停一段短录制，验证后端可用
/wrutils report last
```

**坐标标记以命令为主、木斧为辅**：主路径 `/wrutils sel pos1|pos2` + `player.raycast(...)`（精确、可脚本化）；木斧左/右键（`AttackBlockCallback`/`UseBlockCallback`）作为 Phase 5 的客户端便利层，不引入新语义。

## 关键 API 事实（实现依据，均已从映射 jar 核实）

| 项 | 事实 |
|---|---|
| 方块交互 | `AbstractBlockState.onUse(World, PlayerEntity, BlockHitResult)`；`onUseWithItem(ItemStack, World, PlayerEntity, Hand, BlockHitResult)`（`BlockState` 自身不声明，继承自 `AbstractBlockState`） |
| `ActionResult` | 接口。用 `isAccepted()`；`SUCCESS`/`SUCCESS_SERVER`/`CONSUME` 均为 `ActionResult$Success` record |
| `BlockHitResult` | `BlockHitResult(Vec3d, Direction, BlockPos, boolean)`（**首参是 `Vec3d`**） |
| 假玩家 | `net.fabricmc.fabric.api.entity.FakePlayer`（`fabric-events-interaction-v0` 4.1.1+3b89ecf63e），`FakePlayer.get(ServerWorld, GameProfile)` |
| tick 事件 | `ServerTickEvents.END_WORLD_TICK`（注入 `ServerWorld.tick` TAIL）；`MinecraftServer.getTicks()`、`WorldAccess.getTime()` |
| gamerule | `net.minecraft.world.rule.GameRules`，**按世界**：`ServerWorld.getGameRules().setValue(GameRule<T>, T, MinecraftServer)` |
| 强制区块 | `ServerWorld.setChunkForced(int, int, boolean)` |
| 红石 | `RedstoneView`（`World` 父接口）：`getReceivedRedstonePower` / `getReceivedStrongRedstonePower` / `getEmittedRedstonePower(pos, dir)` |
| 命令下发 | `CommandManager.parseAndExecute(ServerCommandSource, String)`；命令源用 `server.getCommandSource().withSilent()`（OWNERS=OP4，`server` 非 null） |
| 方块串解析 | `BlockArgumentParser.block(RegistryWrapper<Block>, String, boolean)` → `BlockResult` record；`stringifyBlockState(BlockState)`；SNBT 用 `StringNbtReader.readCompound` |

## 分阶段实施

**Phase 0 — 脚手架**（无行为变更）
建包结构；`WrtLog`；`WrtTestEngine.bootstrap()` 挂 `ServerLifecycleEvents.SERVER_STARTED/STOPPED`；mod 入口调用它。
→ 验证：javac 全绿；游戏里正常加载/退出单人存档无异常。

**Phase 1 — 最小可运行闭环（tracer bullet，最关键的验证）**
`TestDefinitionLoader` 只支持 `place`/`use`/`assert blockstate`；`TestScheduler`（`END_WORLD_TICK` + 相对 tick）；`TestPlayerProvider` + `BlockInteractor`（照搬 use 序列 + 定位）；`BlockStateMatcher`；聊天报告。**不录制、不 NBT、不红石、不隔离。**
→ 验证：javac；游戏里——tick2 点拉杆、tick3 断言 `lever[powered=true]` **通过**；故意写错期望 → **失败并打印可读信息**。这一阶段就证明"假玩家点击真的让方块状态变了"，是最大的技术风险点。

**Phase 2 — 数据模型补全 + 世界隔离**
完整 JSON schema；世界/配置目录布局；`/wrutils test list|show|new|reload`；`WorldPrep`（清区域/清实体/`setChunkForced`/gamerule 存取还原）；timeout 与 abort 清理。
→ 验证：同一测试**连跑 10 次结果完全一致**；测试区残留旧方块被清干净；测试区离玩家 200 格外仍能跑（证明强制区块生效）；中途退出不泄漏强制区块/gamerule（`/forceload query`、`/gamerule` 核对）。

**Phase 3 — 断言深度 + 报告**
`NbtSubsetMatcher`、`RedstoneMatcher`、`Failure` 记录、`ReportWriter`（JSON+TXT+聊天+日志）、`runall` 总表。
→ 验证：漏斗/比较器/侦测器/中继器各一个测试；箱子 NBT 子集匹配；拉杆旁红石粉 `received=15`、拉回后 `0`；报告文件可打开且结构正确。

**Phase 4 — 录制后端**
`RecordingBackend`/`RecordingSession`/`ServerReplayBackend`/`NoopRecordingBackend`/`RecordingBackends`；自建配置；每测试 start/stop；通过传 `save=false`；失败保留 + 文件定位兜底；`recording status|test`；`SERVER_STOPPING` 时 abort。
→ 验证：javac（**不需要 ServerReplay jar**）；游戏里（装 ServerReplay + Fabric Language Kotlin）——`/wrutils recording test` 能生成 `.mcpr`，**把它拷进客户端 `./replay_recordings/` 并用 ReplayMod 实际打开确认能看到红石机器**（同时确认最外圈区块没被裁掉，即外扩 1 区块生效）；失败的测试留下 `.mcpr`、通过的什么都不留；**卸载 ServerReplay 后再跑一遍全部测试**，验证 Noop 降级无异常且报告完整。

**Phase 5 — 玩家体验（可选）**
木斧选区 + 选区线框渲染（`fabric-networking-api-v1` 自定义 payload，服务端权威）+ HUD 进度；`test new` 从选区生成骨架；`"trace": true` 变更日志；可选 `/tick sprint` 加速（带自动还原）。

## 验证

### 编译期（本环境唯一的自动验证手段）

本仓库**无法用 gradle 构建**：fabric-loom 1.18.2 要求 JVM 25，环境只有 Java 21（`gradle classes` 在配置阶段就失败）。等价的类型检查：

```bash
LOOM=/workspaces/claude-in-docker-main/WRUtils1_21_11/.gradle/loom-cache
MC=$LOOM/minecraftMaven/net/minecraft/minecraft-common-2ae02fda0f/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-common-2ae02fda0f-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar
MCC=$LOOM/minecraftMaven/net/minecraft/minecraft-clientOnly-2ae02fda0f/1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2/minecraft-clientOnly-2ae02fda0f-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar
# Fabric API（已 remap，yarn 名）
CP="$MC:$MCC:$(find $LOOM/remapped_mods -name '*.jar' ! -name '*sources*' | tr '\n' ':')"
# /tmp/gh 缓存：sponge-mixin、brigadier、datafixerupper、authlib、netty、guava、log4j、jspecify…
CP="$CP$(find /tmp/gh/caches/modules-2/files-2.1 -name '*.jar' ! -name '*sources*' | tr '\n' ':')"
# ~/.gradle 缓存：gson、unpick、fabric-api 元数据
CP="$CP$(find ~/.gradle/caches/modules-2/files-2.1 -name '*.jar' ! -name '*sources*' | tr '\n' ':')"
javac -proc:none -nowarn --release 21 -d /tmp/wrt-out -cp "$CP" \
  $(find src/main/java src/client/java -name '*.java')
echo "退出码: $?"
```

注意：**两处 gradle cache 缺一不可**（mixin/brigadier/datafixerupper 只在 `/tmp/gh`）；`-proc:none` 必须加（否则 Mixin 注解处理器因缺混淆映射而误报）；退出码必须显式检查，别把输出管道给 `tail` 后拿 `$?`。
作用范围仅限**类型检查**：不校验资源、refmap 与任何运行时行为。

### 游戏内（行为验证，必须做）
按上面各 Phase 的"→ 验证"逐条执行。`run/` 目录已就绪，`.idea/runConfigurations/Minecraft_Client.xml` 是客户端入口（但本环境 gradle 跑不起来，需要在有 JVM 25 的环境里运行）。

## 风险与待实测项

**高（阻断级，先验证再往下写）**
1. `FakePlayer` + `onUseWithItem` 在拉杆/按钮/中继器/漏斗/比较器上的实际效果（Phase 1 核心验收点）。
2. `/replay stop chunks named <label> <save?>` 的准确语法与 `save` 是否真的生效（搜索结果一致声称支持且默认 true，但 WebFetch 被网络策略拦截，未能读原文）。
3. ServerReplay 的 `chunk_recording_strategy` 默认值已确认是 `"always"`；但需实测在**无真实玩家**的测试区里是否真的录到内容。

**中**
4. 红石确定性：即使 `randomTickSpeed=0` + 强制区块，仍有 locationality 引起的邻居更新顺序差异、计划刻/方块事件队列顺序、活塞/中继器固有延迟。必要时提供"整 tick 段内任意时刻满足即通过"的宽松断言（`withinTicks`）。
5. gamerule 是**按世界存储**的全局量，测试期间会改变玩家所在世界的规则 → 必须 `finally` + `SERVER_STOPPING` 双保险还原，并做一次"孤儿还原"自检。
6. 与玩家同时建造的竞态：写死"测试是独占操作"，用 `currentRun != null` 锁拒绝并发 run 并在聊天里警告。
7. `FakePlayer` 的 GameMode / abilities 会影响 `onUse` 里的 `isCreative()` 分支；`interactionManager.changeGameMode` 会经过被吞掉的 `networkHandler`，需实测。
8. 会开界面的方块（箱子/工作台）`openHandledScreen` 返回 empty → 测不了"玩家打开了容器"，写进文档。
9. 所有引擎逻辑必须在服务端线程：用 `server.isOnThread()` 守卫，非服务端线程调用直接抛异常。

**低**
10. javac 脚手架抓不到运行时 NPE、JSON 解析语义、Mixin refmap 问题——所以 Phase 1–4 不写 Mixin，行为相关的一切都在游戏里跑。
11. 报告 JSON 的 Gson 往返（尤其 SNBT 里的转义引号）。

## 需要新建/修改的关键文件

- `src/main/java/com/yiming/wrutils1_21_11/Wrutils1_21_11.java`（修改：挂 bootstrap + LOGGER）
- `src/main/java/com/yiming/wrutils1_21_11/test/**`（新建：引擎、模型、调度、执行、断言、报告）
- `src/main/java/com/yiming/wrutils1_21_11/replay/**`（新建：录制后端抽象与实现）
- `src/main/java/com/yiming/wrutils1_21_11/command/**`（新建：命令树与选区）
- `src/main/resources/fabric.mod.json`（修改：`recommends` 里加 `server-replay`）
- `src/client/java/com/yiming/wrutils1_21_11/client/**`（Phase 5 才动：渲染与 HUD）
- 复用现有：`Wrutils1_21_11Client`（键位注册模式，Phase 5 的木斧/键位照此写）
