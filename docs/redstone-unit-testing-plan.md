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

**全部测试逻辑放 `src/main`**（服务端侧；单人集成服务器的服务端与客户端同 JVM，命令在服务端线程执行）。`src/client` 只放 Phase 5/6 的编辑器 GUI 与可选渲染/HUD。

```
src/main/java/com/yiming/wrutils1_21_11/
├── Wrutils1_21_11.java              # 改：挂 WrtTestEngine.bootstrap() + LOGGER
├── WrtLog.java                      # 新增：统一 LoggerFactory
├── test/
│   ├── WrtTestEngine.java           # 单例：生命周期 + 当前 run + 对外 API
│   ├── model/                       # TestDefinition / Step(sealed) / PlaceStep / UseStep
│   │                                #   / AssertStep / Expectation / NbtMatch / RedstoneExpect
│   │                                #   / AreaSpec / Region / CleanupMode
│   ├── io/                          # TestDefinitionLoader(Gson) / TestPaths
│   ├── runner/                      # TestScheduler / TestRun / RunState / WorldPrep
│   ├── exec/                        # TestPlayerProvider(FakePlayer) / BlockInteractor
│   ├── assertion/                   # AssertionEngine / BlockStateMatcher / NbtSubsetMatcher
│   │                                #   / RedstoneMatcher / Failure
│   └── report/                      # TestResult / ReportWriter
├── replay/
│   ├── RecordingBackend.java        # 接口（无 ServerReplay 编译期依赖）
│   ├── RecordingSession.java        # 接口 stop(server, keep)
│   ├── RecordingTarget.java         # 录制目标（标签/维度/中心区块/半径）
│   ├── ServerReplayBackend.java     # 纯字符串命令实现
│   ├── NoopRecordingBackend.java    # 降级
│   ├── RecordingBackends.java       # 探测 + 选择
│   ├── RecordingConfig.java         # 自建配置 config/wrutils1_21_11.json
│   └── RecordingFiles.java          # 按标签定位/清理 .mcpr
└── command/                         # WrtTestCommand / WrtSelectionCommand / SelectionManager
```

**Phase 1–4 不引入任何自己的 Mixin**（避免 javac 脚手架无法验证 refmap 的盲区）。

> 各 Phase 落地后的逐包职责、数据流走查与关键不变量，见 `implementation/` 目录：[Phase 1](implementation/Phase1代码实现解释.md)、[Phase 2](implementation/Phase2代码实现解释.md)、[Phase 2 验证手册](implementation/Phase2验证手册.md)、[Phase 3](implementation/Phase3代码实现解释.md)、[Phase 3 验证手册](implementation/Phase3验证手册.md)、[非破坏性可写区调整](implementation/非破坏性可写区调整.md)、[Phase 4](implementation/Phase4代码实现解释.md)、[Phase 4 验证手册](implementation/Phase4验证手册.md)、[Phase 5 代码实现解释](implementation/Phase5代码实现解释.md)、[Phase 5 验证手册](implementation/Phase5验证手册.md)、[Phase 6 代码实现解释](implementation/Phase6代码实现解释.md)、[Phase 6 验证手册](implementation/Phase6验证手册.md)、[Phase 7 代码实现解释](implementation/Phase7代码实现解释.md)、[Phase 7 验证手册](implementation/Phase7验证手册.md)、[Phase 7 评审修复验证手册](implementation/Phase7评审修复验证手册.md)、[代码评审问题清单 2026-10-17](implementation/代码评审问题清单-2026-10-17.md)、[本次工作总结](implementation/总结-2026-10-14.md)、[会话交接记录 2026-10-17](implementation/handoff-2026-10-17.md)、[会话交接记录 2026-10-14](implementation/handoff-2026-10-14.md)、[会话交接记录 2026-10-12](implementation/handoff-2026-10-12.md)。

### 测试定义数据模型与持久化

- 定义存 `<世界存档>/wrutils1_21_11/tests/<id>.json`（坐标绑定地图，随存档走）。
- 报告存 `<世界存档>/wrutils1_21_11/reports/<时间戳>-<id>.json` + `.txt`。
- 配置存 `<runDir>/config/wrutils1_21_11.json`。

方块用 **MC 原生描述串**（`BlockArgumentParser.block(...)`），玩家可直接抄 `/setblock` 的写法；NBT 用 SNBT 字符串；红石用带 min/max 的结构化对象。

```json
{
  "schemaVersion": 3,
  "id": "repeater_delay_toggle",
  "description": "拉杆 -> 中继器，验证 tick2 点击与 tick10 改延迟",
  "dimension": "minecraft:overworld",
  "origin": [100, 64, 200],
  "timeoutTicks": 200,
  "record": true,
  "area": {
    "input": [
      { "min": [0,0,0], "max": [1,0,2] }
    ],
    "cleanup": "clear_input",
    "forceChunks": true,
    "clearEntities": false,
    "gameRules": { "random_tick_speed": 0, "spawn_mobs": false }
  },
  "setup": [
    { "pos": [0,0,0], "block": "minecraft:repeater[facing=north,delay=1]" },
    { "pos": [0,0,2], "block": "minecraft:lever[face=floor,facing=south,powered=false]" },
    { "pos": [1,0,0], "block": "minecraft:chest[facing=up]" }
  ],
  "steps": [
    { "action": "use", "tick": 2, "pos": [0,0,2], "side": "up", "hand": "main" },
    { "action": "assert", "tick": 3, "pos": [0,0,2],
      "expect": { "block": "minecraft:lever[powered=true]" } },
    { "action": "assert", "tick": 3, "pos": [0,0,1],
      "expect": { "redstone": { "mode": "received", "min": 15, "max": 15 } } },
    { "action": "assert", "tick": 30, "pos": [1,0,0],
      "expect": { "nbt": "{Items:[{Slot:0b,id:\"minecraft:redstone\",count:1}]}", "nbtMatch": "subset" } }
  ]
}
```

- **顶层 `schemaVersion` 必填**（当前为 `3`）。坐标语义与 `area.input` 的形状都由它决定：
  **缺字段或版本号不认识一律直接拒绝**——两种语义无法从内容上区分，猜错就是静默跑错，宁可炸。
- **`dimension` 必须与执行者所在世界一致**：启动前校验，不一致直接拒绝（单跑显示「启动失败」，
  批跑记 `ABORTED`）；不会自动切换维度。
- **`pos` 与 `area.input` 里的坐标是相对 `origin` 的偏移**（`origin` 自身恒为**绝对**坐标）。
  "整体平移一台机器"因此退化成**改一个 `origin`**——存盘后 JSON 只差这一个字段。
- **可写区是数组**（`schemaVersion 3` 起）：`place`/`use` 落在**任一**矩形内即可，
  用于"两片互不相邻的输入区、且不希望中间那片也被放开"的场合。空数组 ≡ 不允许任何写入。
- `setup` 是隐式 tick 0、按书写顺序执行的放方块。**模型层保留 `setup` 与 `steps` 两个独立列表**（游戏内 GUI 编辑器需要区分二者），脱糖推迟到 `TestRun` 构造时做，引擎内部仍是单一时间轴。
- `steps` 的 `tick` 是**相对 tick**（相对 run 开始时的 `world.getTime()`）。同一 tick 内先执行全部 `use`、再执行全部 `assert`，消除歧义。
- 反序列化用 Gson（已在依赖里）+ 手写 `"action"` 分派（比 `RuntimeTypeAdapterFactory` 报错更可读）。

### 时序调度引擎

- 用 **`ServerTickEvents.END_WORLD_TICK`**（只处理 run 对应的 `ServerWorld`）。它注入 `ServerWorld.tick` 的 TAIL；注意该方法在 `/tick freeze` 下**仍被无条件调用**（`MinecraftServer.tickWorlds` 里对 `ServerWorld.tick` 的调用不检查 `shouldTick`，那个检查只门控 GameTest），所以回调触发次数等于**服务端 tick 次数**，而非"有效世界 tick 次数"。断言看到的是该 tick 全部方块/方块实体 tick 完成后的状态。
- **不要用 `END_SERVER_TICK`**：它在所有世界之外触发、拿不到"哪个世界"，且 freeze 时服务端循环仍在跑、`server.getTicks()` 继续增长，会让"第 N tick"与世界状态脱钩。
- tick 基准 = **自维护计数器**，**不用 `world.getTime()`**。每帧回调里先查 `world.getTickManager().shouldTick()`：为 `false`（freeze）直接 return；为 `true` 才 `relativeTick++` 并推进。这样 ① freeze 时自然暂停且不计数（`world.getTime()` 虽然也停，但语义不如显式检查清晰）；② 首个驱动帧精确等于 tick 0——用 `world.getTime()` 会因命令执行时机（晚于当 tick 的 `END_WORLD_TICK`）而漂到 1，压缩 `setup` 与首个 `use` 之间的间隔；③ 不依赖 gametime 这个语义偏"游戏时间"的值。步骤预排序成 `(tick, phase, index)` 列表，每帧推进指针（O(1) 均摊）。
- **测试区域与副作用**（`WorldPrep`，run 开始前一次性做完；全局量由下面「三条收尾路径」还原）——**非破坏性**（2026-10-13 起；Phase 2 的"清空整片 `origin±radius`"语义已废弃）：
  1. **可写区**：`area.input`（**多个**矩形 `{min,max}`）是**唯一允许被修改**的范围。
     `place`/`use` 的坐标必须落在**任一**矩形内，否则**在 `TestRun` 构造时即拒绝**（抛可读错误、不碰世界）；
     报错里会用 ` | ` 列出全部可写区。**机器本体与断言位置一律只读**——适配"在已建好的红石机器上测试"这一主要场景。
  2. `cleanup`：可写区的处理策略。当前**仅实现 `clear_input`**（跑前把可写区清成空气、`NOTIFY_ALL`、跑后不还原）；`none`/`snapshot` 已在 schema 预留但**尚未实现**，选用会抛错。
  3. `forceChunks`（**默认 false**）：开启时先加载"测试涉及范围"（每个可写区与每个步骤坐标**逐区域**，不取包围盒）的区块，调 `world.setChunkForced(cx, cz, true)`（结束时 `false`）。`cleanup=snapshot` 且未开启时，要求这些区块**已经加载**；有未加载的在启动前直接拒绝（不主动加载、不触发生成），避免把空气拍进快照。该检查只发生在启动时；需要测试全程保持加载时用 `forceChunks=true`。测试点较远时才需显式开启；开启后 ServerReplay 的区块录制才有保障（它"开始时会加载区块，之后不再强制加载"）。
  4. `clearEntities`（**默认 false**）：清测试范围内 `ItemEntity`/`ExperienceOrbEntity`。`FakePlayer` 不在 `PlayerManager` 里，不会被误清。
  5. gamerule：通过 `world.getGameRules().setValue(...)`（1.21.11 的 `GameRules` 在 `net.minecraft.world.rule` 包，**按世界**存储），记录原值并在结束时还原；`SERVER_STOPPING` 时也要还原。
  6. **不冻结 tick 速率**（`ServerTickManager.setFrozen(true)` 会让世界停 tick；驱动回调虽仍触发，但基准计数器不再增长，run 会一直挂起到解冻为止——不会崩，但测试不会自行结束）。
- **状态机**（单例，同一时刻只允许一个 run）：
  `IDLE → PREPARING → RECORDING → RUNNING → FINISHING → DONE|ABORTED → IDLE`
  断言失败**不中断**，继续跑完以一次暴露全部失败点；超时（超过 `timeoutTicks`）或异常 → `ABORTED`，仍走 `FINISHING` 清理（`keep=true`）。
  实现上简化为显式 `RUNNING → DONE|ABORTED`：`IDLE` 由 `currentRun == null` 隐式表示，
  准备/录制/收尾都在构造或 `finish()` 内同步完成，不单独暴露 `PREPARING`/`RECORDING`/`FINISHING`。

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
- **另有一个"最小半径"坑**（2026-10-14 实测）：只按测试区大小算半径，小测试会录成 3×3 区块，回放里出了那点范围就是虚空 → 看起来像"视野内渲染距离非常近"。故取 `max(测试跨度, recordingMinRadiusChunks=8) + 1`。
- **相机不受控**：`chunks` 模式的回放 `selfId = -1`，在 ReplayMod 里是**自由相机**，初始位置跟随录到的玩家位置（即"你当时站哪"）。想打开就在机器旁，跑测试前 `/tp` 过去。
- **前后预留**（2026-10-14 加）：`recordingPaddingSeconds`（默认 1.0）在测试**前后各**留一段录制，
  让片头/片尾有静置；只在真的录制时生效，且不计入 `relativeTick`。
- **回放可查看性（已确认可行）**：`default_encoding` 默认就是 `"replay_mod"`，产出的 `.mcpr` 可被**客户端 ReplayMod 直接打开**（把文件拷进客户端的 `./replay_recordings/` 即可）。chunk 模式不与玩家视角绑定，回放里是**自由相机**，正好适合观察红石结构。1.1.0+ 还支持**纯服务端预览** `/replay view chunks "<name>" "<时间戳>"`（查看时该玩家被临时视为离线）。注意：ServerReplay 的录制与客户端自录并非 100% 等同（如 boss bar 不录），且与改动网络包的模组可能冲突（有 `ignore_custom_payloads` 配置可关）。
- 已确认：配置在 `./config/server-replay/config.json`，`chunk_recording_path` 默认 `./recordings/chunks`，**`chunk_recording_strategy` 默认 `"always"`**（不需要真实玩家在场也会录；`chunk_contains_player` 只对 Flashback 格式生效）。
- 降级：探测失败（`FabricLoader.isModLoaded("server-replay")` 且 dispatcher 有 `replay` 根节点，二者都查）→ `NoopRecordingBackend`，测试照常跑、报告照常出，只 warn 一次。`fabric.mod.json` 里把 server-replay 写成 **`recommends`** 而非 `depends`（它还需要 Fabric Language Kotlin）。

### 命令

全部 `CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK)`（OP 2，单人房主默认满足）。参数用原版 argument type 以拿到 tab 补全（`BlockPosArgumentType`、`BlockStateArgumentType`、`IdentifierArgumentType`…）。

```
/wrutils test list [filter]
/wrutils test show <id>
/wrutils test new <id> [name]          # 从当前选区生成骨架 JSON
/wrutils test run <id> [--record|--no-record]
/wrutils test runall [filter]
/wrutils test stop                     # 中止当前 run / 清空批跑队列（按 ABORTED 收尾）
/wrutils test delete <id>              # 删除测试文件（编辑器列表里也有入口，带二次确认）
/wrutils test reload                   # 重新扫描 tests 目录
/wrutils sel pos1|pos2 [<pos>]         # 省略坐标时取玩家视线命中的方块
/wrutils sel clear | info
/wrutils recording status | test       # test = 立刻起停一段短录制，验证后端可用
/wrutils report last
```

测试 id 参数（`show` / `run` / `delete` / `roundtrip`）带 Tab 补全：从 `tests/` 目录读取 id 并按前缀过滤。

**坐标标记以命令为主、手持工具为辅**：主路径 `/wrutils sel pos1|pos2` + `player.raycast(...)`（精确、可脚本化）；**燧石**左/右键（`AttackBlockCallback`/`UseBlockCallback`）作为 Phase 6 的客户端便利层，不引入新语义。
（2026-10-15 定：工具选用**燧石**而非木斧——燧石原版无左键行为、无右键用途，当"魔杖"的代价只剩"左键挖不动方块"。另加**总开关**键位，默认开，可随时关掉让燧石恢复原版。）

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
→ 验证：`scripts/typecheck.sh` 全绿；游戏里正常加载/退出单人存档无异常。

**Phase 1 — 最小可运行闭环（tracer bullet，最关键的验证）** ✅ 已完成（2026-10-12 游戏内实测通过）
`TestDefinitionLoader` 只支持 `place`/`use`/`assert blockstate`；`TestScheduler`（`END_WORLD_TICK` + 相对 tick）；`TestPlayerProvider` + `BlockInteractor`（照搬 use 序列 + 定位）；`BlockStateMatcher`；聊天报告。**不录制、不 NBT、不红石、不隔离。**
→ 验证：javac；游戏里——tick2 点拉杆、tick3 断言 `lever[powered=true]` **通过**；故意写错期望 → **失败并打印可读信息**。这一阶段就证明"假玩家点击真的让方块状态变了"，是最大的技术风险点。

**Phase 2 — 数据模型补全 + 世界隔离** ✅ 已完成（2026-10-12 游戏内实测通过）
> **⚠ 语义已于 2026-10-13 被取代**：Phase 2 的 `isolate.radius/clearArea`（清空 `origin±radius`；该键后改名 `area`）是**破坏性**的，已废弃 → 换成 `area.input`（可写区）+ `area.cleanup` 的**非破坏性**方案，`forceChunks`/`clearEntities` 默认也改为 `false`。下列"清区域"表述仅作历史记录；现行语义见上文「测试区域与副作用」。
完整 JSON schema；世界/配置目录布局；`/wrutils test list|show|new|reload`；`/wrutils sel pos1|pos2|clear|info`（`test new` 依赖选区，故与它同期落地）；`WorldPrep`（清区域/清实体/`setChunkForced`/gamerule 存取还原）；timeout 与 abort 清理。
→ 验证：同一测试**连跑 10 次结果完全一致**；测试区残留旧方块被清干净；测试区离玩家 200 格外仍能跑（证明强制区块生效）；中途退出不泄漏强制区块/gamerule（`/forceload query`、`/gamerule` 核对）。

**Phase 3 — 断言深度 + 报告** ✅ 已实现（2026-10-13），`typecheck` 全绿；验收状态见 [Phase 3 验证手册](implementation/Phase3验证手册.md) 标题下的**勾选清单**。
> 首轮游戏内实测（2026-10-13）暴露两处并已修：① `BlockArgumentParser.block(...).nbt()` 在方块串不带 `{}` 时返回 **null**（不是空 compound），导致所有无 NBT 的 `place` 在 tick 0 NPE→ABORTED；② `ignoreKeys` 原实现递归生效，会误伤 `Items` 内物品自身的 `id`，已收窄为**只作用于顶层**（对齐原版 `/execute if data`）并把 `components` 加入默认忽略，否则 `exact` 对任何方块实体都必失败。**修复后需重跑验证手册全流程。** ③ `runall` 在某个测试**启动失败**时卡死（推进只挂在 `onEndWorldTick`，`currentRun` 一空就断链）——`startNext()` 改为循环推进。④ **启动失败不落盘**：批跑里启动失败的测试只有聊天报错、磁盘上没有报告（失败记录缺一条）——已改为写一条 `ABORTED`/`kind=startup` 报告。⑤ 失败的 `exp` 之前显示**补全默认属性后**的方块状态（如 `lever` 的 `face=wall`），与非严格模式实际只比"写了的属性"不符、误导读者——已改为显示作者写的原串。详见 [Phase 3 实现说明](implementation/Phase3代码实现解释.md)。
`NbtSubsetMatcher`、`RedstoneMatcher`、`Failure` 记录（加 `kind`）、`ReportWriter`（JSON+TXT+聊天+日志同源）、`runall` 总表。
**计划外但必要的一处补全**：`place` 步骤此前丢弃方块串里的 NBT，导致"箱子/漏斗里有物品"无法建立，NBT 断言无从验证 → 现在经 `NbtReadView.create(...)` 把 NBT 写进方块实体（1.21.11 的 `BlockEntity.read` 收 `ReadView` 而非 `NbtCompound`）。详见 [Phase 3 实现说明](implementation/Phase3代码实现解释.md)。
→ 验证：漏斗/比较器/中继器各一个测试 + 箱子 NBT 子集/全等 + 拉杆旁红石粉 `received=15` 再 `0`；报告文件可打开且结构正确；`runall` 总表。**侦测器样例暂缺**（1 tick 脉冲需要 `withinTicks`，见风险 #4）。逐条步骤见 [Phase 3 验证手册](implementation/Phase3验证手册.md)。

**Phase 4 — 录制后端** ✅ **已完成**（2026-10-14，`typecheck` 全绿 + 游戏内实测通过）
> 验收状态见 [Phase 4 验证手册](implementation/Phase4验证手册.md) 标题下的**勾选清单**（§1–§5 已勾选，§6 停机保留待测）。
> 实测中修正了两处：① 录制半径原按测试区大小算，小测试只录 3×3 区块 → 回放"一步到虚空"；改为 `max(测试跨度, 8) + 外扩1`。② 相机不受本命令控制（`selfId=-1` 自由相机，跟随录到的玩家位置）——跑测试前 `/tp` 到测试区即可。另按需求加了前后预留 `recordingPaddingSeconds`（默认 1.0，测试前后各留一段）。
`RecordingBackend`/`RecordingSession`/`RecordingTarget`/`ServerReplayBackend`/`NoopRecordingBackend`/`RecordingBackends`/`RecordingConfig`/`RecordingFiles`；`TestDefinition.record`（默认 `true`）+ `run <id> [--record|--no-record]`；每测试 start/stop；通过传 `save=false`（外加延迟 glob 兜底删除）；失败保留 + 尽力定位产出文件（写进报告 `recording` 字段）；`/wrutils recording status|test`；`SERVER_STOPPING` 保留现场。详见 [Phase 4 实现说明](implementation/Phase4代码实现解释.md)。
→ 验证：javac（**不需要 ServerReplay jar**）；游戏里（装 ServerReplay + Fabric Language Kotlin）——`/wrutils recording test` 能生成 `.mcpr`，**把它拷进客户端 `./replay_recordings/` 并用 ReplayMod 实际打开确认能看到红石机器**（同时确认最外圈区块没被裁掉，即外扩 1 区块生效）；失败的测试留下 `.mcpr`、通过的什么都不留；**卸载 ServerReplay 后再跑一遍全部测试**，验证 Noop 降级无异常且报告完整。

**Phase 5 — 游戏内测试编辑器** ✅ **已完成**（2026-10-14，5.1–5.5 全部落地并实测通过）

（原始三块需求之外的新增项。）让玩家在游戏里增删改测试用例，不必手写 JSON。前置：Phase 1 已保证**模型与 JSON 同构**（见上文"关键不变量"），这是无损往返的基础。

- `TestDefinitionWriter`（模型 → JSON），与 `TestDefinitionLoader` 成对。**往返保真**（load→save→load 对象相等）是硬验收，也是"同构"唯一的证据。
- GUI Screen：测试列表 + `setup` 列表 + `steps` 列表（增删、重排、改 tick/坐标/动作）。
- 方块串实时校验：复用 `BlockArgumentParser.block(...)`，解析失败即标红，不让坏串落盘。
- 坐标拾取：选中某步 → 点击世界里的方块填入坐标。需要一套"编辑会话"状态（哪一步在等选点、选完如何回到 GUI）。**与 Phase 6 的燧石选区共用底层**——本阶段先做则基础在此建立，Phase 6 只做渲染与扩展。
- 编辑 ↔ 运行互斥：复用引擎已有的 `currentRun != null` 闸。
- **坐标存法已定（2026-10-14）**：**相对 `origin` 存储**，顶层 `schemaVersion: 2`——`origin` 恒为绝对值，其余坐标为偏移。
  换算**只发生在 IO 层**，模型与引擎保持绝对坐标。无 `schemaVersion` 的旧格式**直接拒绝**（宁可炸，也不静默读错）。
  于是"整体平移"退化成"改一个 `origin`"，UI 上只需补一条平移操作。
- **5.4（未做）：坐标拾取 + 整体平移**。选中条目 → 关屏 → 准星对准方块 → 按**专用键位**填入坐标（不占左键）。
  拾取会话状态必须放客户端的 `EditorSession`（跨 Screen）。与 Phase 6 的燧石选区共用底层。
- **5.5（排在 5.4 之后，2026-10-14 定）：`area` 支持多选区**。动机是"两片**互不相邻**的可写区、且不希望中间那片也被放开"；
  若只是形状不规则，现有单矩形取包围盒已够用。
  改动面：`AreaSpec.input` 由单个 `Region` 变 `List<Region>`；越界校验改成"落在**任一**矩形内"，
  `clearInput` / `chunkRegion` 逐区域处理——**引擎侧很小**，主要工作量在 GUI 的区域列表与两端拾取。
  按"旧格式直接舍弃"的既定偏好，这会升 **`schemaVersion: 3`**，v2 文件直接被拒、需重拷一次。

→ 验证：GUI 里新建测试、加 setup 与步骤、保存后 `/wrutils test run <id>` 跑通；打开 JSON 确认字段干净、无冗余；load→save→load 三份相等；用 GUI 改一个期望值后重跑，结果随之变化。逐条步骤见 [Phase 5 验证手册](implementation/Phase5验证手册.md)。

**Phase 6 — 玩家体验（可选）+ 多人服务器支持**

燧石选区 + 选区线框渲染（`fabric-networking-api-v1` 自定义 payload，服务端权威）+ HUD 进度；`test new` 从选区生成骨架；可选 `/tick sprint` 加速（带自动还原）。

> **燧石选区 + 线框渲染 ✅ 已完成并游戏内验收通过（2026-10-15，客户端优先版；`typecheck` 全绿，手册 §1–§16 全通过）**
> `SelectionTool`（`AttackBlockCallback`/`UseBlockCallback`：手持**燧石**左键 pos1 / 右键 pos2）
> + `SelectionRenderer`（`WorldRenderEvents.BEFORE_DEBUG_RENDER` 画线框），写进与 `/wrutils sel` 同一份 `SelectionManager`。
> **原为有意偏离方案**：一开始没走 payload，而是客户端直调 `SelectionManager`（单人下客户端与服务端同 JVM，
> 与编辑器直读 `isRunning()` 是同一套既有假设）。**2026-10-17 多人支持落地时已补上 payload 上报**——
> 本地那份保留（线框要即时画，等一个网络来回有肉眼可见的延迟），另经 `SelectionUpdate` 同步服务端那份；
> 渲染与取点代码确实一行没动，与当初的判断一致。
> 燧石在这套用法下就是根"魔杖"：手持时左键挖不动方块（右键无额外代价，燧石原版没有右键用途）。
> 2026-10-15 游戏内验收（§1–§9）用的是木斧；同日改为**燧石**，机制未变，仅 `SelectionTool.active(...)` 判定的物品换了，需以手册 §10/§11 复核。
> 2026-10-15 增补：加了**总开关**（键位默认 J，`SelectionTool.isEnabled()`，默认开）与 `SelectionHud`
> （挂 `VanillaHudElements.CHAT` 之后，左下角显示开关状态；**只在手持燧石且开关已开时画**，否则整块不画）。判据因此变为
> "客户端侧 + 开关已开 + 手持燧石 + 未在跑测试"。
> **2026-10-16 增补：测试进度 HUD ✅ 已完成（待游戏内验收）**。
> 新增 `test/runner/TestProgress.java`（不可变快照）与 `client/progress/ProgressHud.java`（画在屏幕**左上角**）。
> `WrtTestEngine` 加 `volatile TestProgress progress`，在 `onEndWorldTick` 里**每 tick 由服务端线程重建整份快照**——
> 不让 HUD 逐字段去读 `TestRun`：那是渲染线程读、服务端线程写，逐字段读既没有 happen-before 边、
> 也拿不到一致的一组数（可能读到跨 tick 的混合值）。`TestRun` 只加了 `progress(queued)` 与 `TestScheduler.total()`。
> 显示两行：`[WRT] 运行中 <id>` / `步骤 x/y  断言 n  失败 m  tick t/T  队列剩 k`（失败>0 时第二行标红）。
> 放**左上角**而不是像选区 HUD 那样放左下角：左下角是原版聊天区，而测试结果本来就打在那里。
> `/tick sprint` **正式跳过**（方案本就标为"可选"，2026-10-17 用户拍板不做）；多人支持见下。

**多人服务器支持 ✅ 已完成并游戏内验收通过（2026-10-17，§22–§35 全通过）**

**做了什么**：新建 `net/` 包（common 侧）与 `client/net/` 包，共四个 payload 类型——
`TestEditorRequest` / `TestEditorResponse`（C2S/S2C，共用 `op` 枚举 LIST / READ / WRITE / DELETE / SELECTION）、
`TestStatusUpdate`（S2C，每 tick 推送运行状态）、`SelectionUpdate`（C2S，燧石选区上报）。
文件 IO 按原计划**归服务端**：`TestPaths` / loader / writer **一行未改**，只是调用方从客户端搬到了 `TestEditorServer`。
**线格式果然现成**：`WRITE` 直接传 `TestDefinitionWriter.toJson` 的 JSON 字符串，服务端 `parse` 校验后再原子写回；
方块串校验也确实留在客户端没动。

**三处与原计划的出入，都记在这里**：

1. **payload 从"约 5 个"收敛到 4 个**，其中"删"**砍掉**——编辑器根本没有删除测试文件的入口
   （界面上两个"删除"删的是内存里的条目与可写区；当时全仓唯一一处 `Files.delete` 属于回放清理）。
   > **2026-10-17 补记（P7b）**：删除测试文件已补上——`TestEditorOp.DELETE`（服务端校验 id 后
   > `Files.delete`）、`/wrutils test delete <id>`，以及编辑器列表的「删除」按钮
   > （`ConfirmScreen` 二次确认）。上面的"砍掉"只代表 Phase 6 当时的取舍。
2. **不照抄 `replay/` 的 `Backends.select + Noop` 降级骨架**。那套骨架的前提是"存在多个互斥后端"
   （装没装 server-replay），而 Fabric 网络永远可用、只有一种机制——照抄只会多出一个**永远不会被选中**的
   `NoopEditorBackend`，是形状模仿而非结构相同。
3. **单人也不再走直读捷径**：`isRunning()` / `progress()` / 文件 IO 在单人下同样走 payload。
   代价是多了序列化、状态晚一 tick；换来的是单人多人**同一条码路**——原先那条"只在单人成立"的捷径
   （即上面 `SelectionTool` 的旧写法）正是"单人可以、多人坏"的温床。**这是本轮最大的一处取舍。**

请求与响应合成两个类型而非拆成多个，是因为这些操作在服务端**共用同一段校验**（权限 + id 白名单），
拆开就要把那两段抄多遍，漏抄任何一遍都是安全漏洞。

**新增的两条服务端校验**（此前根本不需要——因为此前没有客户端输入）：接收器里查 `CommandManager.GAMEMASTERS_CHECK`，
与 `/wrutils` 命令**同一道门槛**（不一致的话，非 op 玩家能绕过命令权限、经编辑器直接读写服务端存档）；
以及 id 白名单，把客户端那条正则**在服务端重跑一遍**——`"../../server.properties"` 是个完全合法的字符串，
它会顺着 `testsDir.resolve(id + ".json")` 逃出 tests 目录。

**一条关键假设**（与多人无关，但单人下全靠它）：集成服务端**暂停时仍然处理网络包**。
反编译核对过：`MinecraftServer.processPacketsAndTick` 里 `packetApplyBatcher.apply()` 是**无条件**执行的，
暂停只影响其后 `tick()` 的 keep-ticking 判断。这与"单人开着背包世界暂停、物品却照常移动"是同一个道理。
若该假设在某版本失效，表现为编辑器**永远停在"加载中…"**。

**验收后修掉的一处缺陷（2026-10-17）**：`ClientTestState` 从"直读单例"换成"网络镜像"时**漏了复位路径**。
服务端是**边沿触发**广播（空闲→空闲不发），于是"最后收到的那条是 `running=true`、却在测试结束前就断了线"
的客户端会**永久卡在"正在跑测试"**——燧石选不了点、编辑器打不开，而选区 HUD（它不查 `isRunning`）照旧显示"开启"。
已在客户端挂 `ClientPlayConnectionEvents` 的 JOIN / DISCONNECT 复位（`ClientTestState.reset()`），
并**按 UUID** 清掉本客户端的选区——**不清全局**：单人开 LAN 时客户端与服务端共用同一个 `SelectionManager`，
清全局会把同房间其他玩家的选区一并抹掉。

> 这条是"直读单例 → 网络镜像"这个替换**必然**带来的新风险：镜像多了一条可能不同步的路径，
> 而原先的直读没有。凡是把本地权威状态换成镜像的地方，都要一并想清楚**什么时候复位**。
> 回归项见手册 §33 / §34。

**同期补上的一处既存空白（2026-10-17）**：编辑器里的"新建"原先一律用 `client.player.getBlockPos()` 当可写区，
**从不读燧石选区**——于是"用燧石圈好了范围、点编辑器新建却建在脚底下"。两条新建入口本该同义，
现改为：有选区就用选区（`origin` 取 min 角，与 `/wrutils test new` 一致），没选区（或只选了一端）才退回玩家脚下。
`EditorSession.reset` 随之多了一个接收初始可写区的重载。回归项见手册 §35。

> **2026-10-17 评审修复（P4）**：多人下上面这条读的仍是**客户端本地**选区镜像，而 `/wrutils sel` 只写服务端，
> 命令圈出的范围不会被编辑器采用。现改为经编辑器协议向服务端请求当前选区（`TestEditorOp.SELECTION`，
> 响应把 `x1 y1 z1 x2 y2 z2` 放在 `json` 字段）；请求失败或旧服务器才退回本地镜像。
> 验证见 [Phase 7 评审修复验证手册](implementation/Phase7评审修复验证手册.md) §17。

**Phase 6 就此收尾（2026-10-17）**：三项（燧石选区 / 进度 HUD / 多人支持）全部验收通过，
`/tick sprint` 正式不做。逐文件职责、四条数据流与关键不变量见 [Phase 6 代码实现解释](implementation/Phase6代码实现解释.md)。

**"最大的成本不是代码而是验证环境"这个判断成立**：环境（`run-server/`、`player001`、`online-mode` 那对开关）
已于 2026-10-17 备齐，见[交接文档](implementation/handoff-2026-10-17.md)的「环境」一节。

> **前提提醒**：本框架的测试会改 gamerule、强制加载区块、**独占世界**（见风险 #6）。多人下它只适合"**服主自己主控的服务器**"，
> 不是给普通玩家用的功能。若目标是开放给任意玩家，与框架的既有假设冲突，应先重新讨论。

## Phase 7 — 引擎能力补全 ✅ 已完成（2026-10-17）

逐文件职责、数据流与关键不变量见 [Phase 7 代码实现解释](implementation/Phase7代码实现解释.md)。

Phase 6 收尾后盘点全部遗留项，Phase 7 只做其中**"schema 已承诺、却仍会抛错或写不出来"**的三件事：

1. `withinTicks`（宽窗口断言）——没有它，1 tick 脉冲类断言必然闪烁，侦测器样例一直缺着；
2. `cleanup: none` / `snapshot`——schema 里有，选用直接抛 `UnsupportedOperationException`；
3. 让上面两项在编辑器里有入口——否则只有手改 JSON 才能用。

**明确不做**：GUI 其余缺口（`expect` 的 `strict`/`nbt`/`redstone`、`use` 的 `side`/`hand`、`AreaSpec` 其余开关、
维度、重命名）、`use` 指定手持物品、删除测试文件、Phase 6 收尾时列出的体验小项。它们排到 Phase 8，
清单见本 Phase 末尾。

### 7.1 `withinTicks`：宽窗口断言 ✅ 已实现并验收通过（2026-10-17，手册 §1–§10 全通过）

实现与本节设计一致，逐条步骤见 [Phase 7 验证手册](implementation/Phase7验证手册.md) §1–§9。
落地时的两处细节：
- 断言步骤**不在 `execute` 里立刻采样**，而是挂进 `pendingAsserts`，由本 tick 步骤循环之后统一采样——
  这样同一 tick 的 `place`/`use` 一定排在采样之前，且窗口的第一次采样就发生在该 tick 末尾。
- 超时判定覆盖"还在等窗口"：`relativeTick > timeoutTicks` 时照常 ABORTED（窗口不能绕过超时）。

**验收中发现并修掉的两处**（2026-10-17）：
- **编辑器对 assert 空方块串的校验不一致**：选中某个 assert 再保存会报「方块串非法」，
  不选中它保存却通过。assert 的空方块串是合法的（只查 nbt / redstone 时没有 block），
  `TestEditScreen.validateAll` 已改为放行，与遍历模型时的规则一致。
- **侦测器样例的时序余量不足**：实测脉冲在点击后 **+2 tick** 才落到红石粉上，
  原窗口 `withinTicks: 2`（2..4）刚好卡在最后一刻；改为 3（2..5），末尾"回到 0"的断言后移到 tick 10。

**问题**：`AssertStep` 只在 `tick` 那一刻采样一次。侦测器/短脉冲的输出只存在 1 tick，精确断言必然闪烁。

**语义（新增，向后兼容）**：

- `withinTicks` 默认 **0**，等于现有语义：只在 `tick` 采样一次。
- `> 0` 时，断言在 **`tick` 到 `tick + withinTicks`（含两端）** 的每个 tick 各采样一次；
  **任一 tick 全条件满足即通过**，立即停止采样、不产生失败。
- 窗口内始终不满足 → 在窗口结束时产出一条失败，`tick` 记窗口末 tick，`why` 里注明
  "期望在 t..t+W 内任一 tick 成立"，`act` 是窗口末的实测值。
- `withinTicks < 0` 在加载时**直接拒绝**（与 schema 其他字段的"宁可炸"一致）。

**为什么不是"延迟到窗口末再判一次"**：脉冲的中间态必须能被捕到，只判末 tick 等于没做。

**改动**：

| 文件 | 改动 |
|---|---|
| `test/model/AssertStep.java` | record 加 `long withinTicks`。**所有构造点都要改**——编译器会挨个指出来 |
| `test/io/TestDefinitionLoader.java`（`:156`） | 读可选键 `withinTicks`（默认 0），负数报错 |
| `test/io/TestDefinitionWriter.java`（`:132-136`） | **仅 `> 0` 时写出**，让现有 JSON 保持干净；往返仍相等（读不到按 0） |
| `test/runner/TestRun.java` | 新增 `List<PendingAssert> pendingAsserts`；`execute` 里 `> 0` 的断言先采样一次，失败则挂进 pending；每 tick 的 `execute` 循环之后统一复查；窗口末仍失败则并入 `failures` |
| `test/runner/TestRun.java`（`:235-245`） | 收尾条件改为 `scheduler.isFinished() && pendingAsserts.isEmpty()`；**超时判定同样覆盖"等待窗口"**。结构改成：能收尾 → 后置预留/完成；否则超时 → ABORTED |
| `client/editor/StepEditor.java` | `convert` / `withTick` / `withPos` / `withMainField` **四处**重建 `AssertStep`，都要带 `withinTicks`（漏一处就会把值清 0） |
| `client/editor/TestEditScreen.java` | 选中 assert 时多一个数字输入框（默认 0，与 tick 同一行或下一行）；非 assert 禁用 |
| `docs/examples/` | 新增 `observer_pulse.json`：侦测器 1 tick 脉冲 + `withinTicks` 断言（Phase 3 欠的样例） |

**不变量**：

1. **窗口采样是只读的**：`AssertionEngine.check` 无副作用，重复调用安全。
2. **`asserts` 计数一次**（步骤开始时），不按采样次数累加；`executed` 也只在步骤执行时 +1。
3. **pending 期间的失败不提前上报**：窗口内任何一次通过都要能把失败"抹掉"。
4. **窗口不能让 run 提前结束**，也不能无视 `timeoutTicks`。
5. **旧的 JSON 与旧行为完全不变**：`withinTicks` 缺省 = 0。

### 7.2 `cleanup`：`none` / `snapshot` ✅ 已实现并验收通过（2026-10-17，§11–§18；§16 体积上限用户暂不测）

实现与本节设计一致；逐条步骤见 [Phase 7 验证手册](implementation/Phase7验证手册.md) §11–§18。
落地时的三处细节：
- 体积检查（上限 100 万格）放在 `prepare()` **最前面**，先于 `forceChunks()`——超限时不该先强制加载大片区块。
- 还原顺序：**方块 → 方块实体 NBT → gamerule → 取消强制区块**；方块实体必须等方块状态恢复后再写。
- 新增示例 `docs/examples/cleanup_snapshot_demo.json`：跑前手动在可写区放一个带 5 个红石的箱子，
  测试把它换成石头，跑完应原样还原（含 NBT）。
- **代码评审后修复（2026-10-17）**：① `snapshot` 在 `forceChunks=false` 时**不再隐式加载**，
  而是要求测试涉及区块已经加载；有未加载的在启动前直接拒绝（不加载、不触发生成），
  `forceChunks=true` 仍显式加载；② 强制加载/清实体改为**逐区域**处理，不再对多选可写区取并集包围盒，
  避免两片不相邻区域之间的间隙被加载或清实体。
  逐条验证见 [Phase 7 评审修复验证手册](implementation/Phase7评审修复验证手册.md)。

**语义**：

| 模式 | 跑前 | 跑后 |
|---|---|---|
| `clear_input`（现状） | 清空全部可写区 | **不还原** |
| `none` | 完全不碰方块 | 不还原 |
| `snapshot` | 记录可写区内**全部非空气方块**及其方块实体 NBT | **精确写回**：快照里有的恢复原状，快照时为空气的重新清成空气 |

**只覆盖可写区**，与"非破坏性"契约一致：机器把方块推出可写区后，那些方块不在快照范围，不还原
（这一点要写进验证手册与已知取舍）。

**改动**（全部集中在 `test/runner/WorldPrep.java`；loader/writer 早已支持这三种模式，**schema 不动**）：

1. `applyCleanup()`：`NONE` → 空操作；`SNAPSHOT` → `captureSnapshot()`；`CLEAR_INPUT` 原样。
2. **`prepare()` 调整顺序**：`forceChunks()` 提到 `applyCleanup()` **之前**。快照要读方块状态，
   未加载的区块读出来是空气，不先加载就会把整片区域记成"本来什么都没有"。
   （清空模式提前加载也无害，行为不变。）
3. `captureSnapshot()`：遍历每个 `Region`，只存**非空气**方块 → `Map<BlockPos, BlockState>`；
   有方块实体的另存 `createNbtWithIdentifyingData(world.getRegistryManager())` → `Map<BlockPos, NbtCompound>`。
4. `restore()`：新增"方块还原"一步，且顺序改为 **方块 → gamerule → 取消强制区块**
   （先还原方块再卸载区块，避免写到半加载的区块）。
   方块还原分**两轮**：先按快照写方块状态（快的用 `Blocks.AIR`），**再**统一写回方块实体 NBT——
   方块实体是 `setBlockState` 之后才存在的，顺序反了会写到旧方块实体上。
   写回 NBT 用与 `TestRun.placeBlock` 相同的 `NbtReadView.create(logging, registryManager, nbt)`。
5. `restore()` 必须**幂等**：沿用现有的 `clear()` 收尾；快照两份 map 还原后清空。

**必须注意**：

- `BlockPos.iterate(min, max)` 返回的是**复用的可变实例**，存进 map 前一律 `toImmutable()`，
  否则所有键都会指向最后一格。
- 快照大小与区域**体积**成正比（不是与非空气数量成正比的是还原遍历）。给一个硬上限
  `MAX_SNAPSHOT_ENTRIES = 1_000_000`，超过就报错并建议缩小可写区——服务器 OOM 比"测试没跑"糟得多。
- `snapshot` 模式的记录与本体方块无关：`record` 仍走原有录制路径。

### 7.3 编辑器最小入口 ✅ 已实现并验收通过（2026-10-17，§19–§24 全通过）

- `TestEditScreen`：assert 行的 `withinTicks` 数字输入框（7.1 已做）。
- `TestAreaScreen`：`cleanup` 三选一——底部「清理:清空 / 保持 / 快照」按钮，点一下切到下一个；
  加载已有测试时显示原值。`forceChunks` / `clearEntities` / `gameRules` 仍不提供入口（保持原值不动）。
- **超时自动抬高**（验收时用户提的建议）：保存时把 `timeoutTicks` 抬到
  「最晚步骤（窗口断言算到窗口末端）+ 100 tick」，**只升不降**；状态栏会提示抬到了多少。
- **按 K 重新打开时恢复上次选中的测试**（同一建议）：列表屏记住最近选中的 id，
  无参打开时用它；从编辑屏返回原本就会带 `session.id()`。
- **返回列表时自动保存**（验收时用户提的建议）：ESC / 「返回列表」都会先校验并保存，
  成功后回到列表并在**列表屏顶部**显示提示；校验失败或保存失败弹出「丢弃离开 / 继续编辑」
  确认框，不会静默丢改动。
- **方块串尾部校验**（验收中 §26 暴露）：`BlockArgumentParser.block(..., String, ...)` 只解析前缀，
  `"minecraft:stone撒地方"` 会被静默当成 `minecraft:stone`。新增 `test/BlockStringParser`
  统一入口，解析后检查 `StringReader` 是否读完；编辑器校验、`place` 步骤、`assert` 的 block
  条件三处共用，坏串既不能落盘，运行时也会明确报错。

### 7.4 验证

1. `scripts/typecheck.sh` 全绿（改 Java 必跑）。
2. **`/wrutils test roundtrip` 仍是 9 相等 / 0 不等**——`AssertStep` 加字段后这是硬验收。
3. 游戏内：
   - `observer_pulse` 通过；把 `withinTicks` 改成 0 → 同一定义**失败**（证明确实是新语义在起作用）。
   - `cleanup: none`：可写区里预置一个方块，跑一个会改它的测试 → 方块保持被改后的样子。
   - `cleanup: snapshot`：可写区里放箱子并写入物品，跑一个会改方块/清空箱子的测试 →
     结束后方块全部复原、箱子物品也在；**可写区外**的方块不还原。
   - `clear_input` 回归：原有行为不变。
4. 回归：`/wrutils test runall` 结果与之前一致。

### 不在 Phase 7 范围（Phase 8 候选）

| 类别 | 项 |
|---|---|
| 编辑器 | `expect` 的 `strict`/`nbt`/`redstone`；`use` 的 `side`/`hand`；`AreaSpec` 的 `forceChunks`/`clearEntities`/`gameRules`；`dimension`；改 id 的"重命名/另存"语义（目前会留下旧文件） |
| 引擎 | `use` 指定手持物品（FakePlayer 现在恒为空手）；跨维度测试（一个定义涉及多个维度；当前为单维度 + 启动校验，见评审清单 U3） |
| 体验 | `SelectionHud` 是否在测试运行中隐藏；`ClientTestEditor.PENDING` 超时清理；服务端 `SelectionManager` 清理离线玩家；单人无作弊档编辑器不可用是否符合预期 |
| 验证欠账 | 按钮元件；`/replay stop ... <save?>` 的 `save` 语义原文确认 |
| 文档 | 方案 `:294` 仍写"5.4（未做）"；`Phase5验证手册` 的"§5.3/5.4 未实现 / 多人不可用"；`Phase6验证手册` 的"sprint 未做" |

## 验证

### 编译期（本环境唯一的自动验证手段）

本仓库**无法用 gradle 构建**：fabric-loom 1.18.2 要求 JVM 25，环境只有 Java 21（`gradle classes` 在配置阶段就失败）。等价的类型检查已固化为脚本：

```bash
scripts/typecheck.sh     # 成功打印 "typecheck: OK"；失败返回非零退出码
```

脚本内部：定位 loom 缓存里 remap 好的 MC jar + Fabric API 模块，加上 `~/.gradle` 与 `/tmp/gh` 两处依赖缓存拼成 classpath，再 `javac -proc:none -nowarn --release 21`。

注意：**两处 gradle cache 缺一不可**（mixin/brigadier/datafixerupper 只在 `/tmp/gh`）；`-proc:none` 必须加（否则 Mixin 注解处理器因缺混淆映射而误报）。
作用范围仅限**类型检查**：不校验资源、refmap 与任何运行时行为。

### 游戏内（行为验证，必须做）
按上面各 Phase 的"→ 验证"逐条执行。`run/` 目录已就绪，`.idea/runConfigurations/Minecraft_Client.xml` 是客户端入口（但本环境 gradle 跑不起来，需要在有 JVM 25 的环境里运行）。

## 风险与待实测项

**高（阻断级，先验证再往下写）**
1. `FakePlayer` + `onUseWithItem` 的实际效果。**拉杆已实测通过**（2026-10-12：`lever_toggle` PASSED，`lever_toggle_fail` 打印了可读失败信息）——假玩家点击确实能让方块状态改变，`BlockInteractor` 照搬的 `useBlock` 序列有效。**仍待验证**：按钮 / 中继器 / 比较器 / 漏斗（各自 `onUse` 实现不同；Phase 3 已为后三者写好样例待跑，漏斗用"不点击、靠 tick 传输"的路径绕开开界面问题，见验证手册 §5）。
2. `/replay stop chunks named <label> <save?>` 的准确语法与 `save` 是否真的生效（搜索结果一致声称支持且默认 true，但 WebFetch 被网络策略拦截，未能读原文）。**（2026-10-14：已实现 `/wrutils recording test` 与失败样例两条实测路径；即使 `save=false` 不生效，`RecordingFiles` 的延迟删除兜底也会把产出的 `.mcpr` 清掉。）**
3. ServerReplay 的 `chunk_recording_strategy` 默认值已确认是 `"always"`；但需实测在**无真实玩家**的测试区里是否真的录到内容。**（2026-10-14：`/wrutils recording test` 就是为这条准备的最小实测。）**

**中**
4. 红石确定性：即使 `randomTickSpeed=0` + 强制区块，仍有 locationality 引起的邻居更新顺序差异、计划刻/方块事件队列顺序、活塞/中继器固有延迟。必要时提供"整 tick 段内任意时刻满足即通过"的宽松断言（`withinTicks`）。**（2026-10-13：`withinTicks` 尚未实现——正因如此，Phase 3 的侦测器样例暂缺：它的输出是 1 tick 脉冲，精确 tick 断言必然闪烁。需要时优先补 `withinTicks`。）**
5. gamerule 是**按世界存储**的全局量，测试期间会改变玩家所在世界的规则 → 必须还原。
   **（2026-10-16 更正）** 原文写"必须 `finally` + `SERVER_STOPPING` 双保险还原，并做一次'孤儿还原'自检"，
   与代码不符：全仓**没有任何 `finally` 块**，也**没有**孤儿自检。实际是**三条**路径——
   ① `WorldPrep.prepare()` 内的 `catch (RuntimeException)` 先回滚再抛（`:45-60`）；
   ② 正常运行收尾走 `TestRun.finish()` → `forceRestore()`（`TestRun.java:359-361`）；
   ③ 停机兜底 `WrtTestEngine.onServerStopping()`（`WrtTestEngine.java:270-281`）。
   幂等靠 `WorldPrep.restore()` 里的两处 `clear()`（`:71`、`:75`）。
   （2026-10-12 实测：正常结束与中途退存档均正确还原。**另注**：1.21.11 重命名了大批 gamerule——`doMobSpawning`→`spawn_mobs` 等，见 [Phase2验证手册](implementation/Phase2验证手册.md) 的踩坑记录。）
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
- `scripts/typecheck.sh`（新增：javac 类型检查脚本，各 Phase 通用）
- `src/main/java/com/yiming/wrutils1_21_11/test/io/TestDefinitionWriter.java`（Phase 5 才动：模型 → JSON，与 Loader 往返保真）
- `src/client/java/com/yiming/wrutils1_21_11/client/**`（Phase 5/6 才动：编辑器 GUI、渲染与 HUD）
- 复用现有：`Wrutils1_21_11Client`（键位注册模式，Phase 6 的键位照此写；选区工具见 `client/selection/`）
