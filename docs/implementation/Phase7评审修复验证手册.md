# Phase 7 评审修复验证手册

> **覆盖代码评审发现并已修复的两个高优先级问题：**
>
> 1. `cleanup: snapshot` 在 `forceChunks: false`（默认）时没有先加载区块，快照会把可写区读成空气，
>    还原时反而把可写区清空；
> 2. 多个可写区的强制加载 / 清实体仍按并集包围盒处理，两片互不相邻区域之间的间隙也会被加载或清实体。
>
> **2026-10-17 决策变更**：`snapshot` 的正确性策略从「自动加载区块」改为「未加载就拒绝执行」。
> §2–§6 是前一版自动加载行为的验证记录（其中 §6 的 `forceChunks=true` 回归仍有效）；
> 当前未加载语义见 **§13–§16**。§17 覆盖多人选区（P4），§18–§20 覆盖其余评审修复（P5/P6/P7a）。
>
> 方案见[主方案 Phase 7](../redstone-unit-testing-plan.md) 的「测试区域与副作用」与 §7.2。
>
> ⚠️ 本次改了 Java 代码 → **必须重启游戏**；只改 JSON → 不用，引擎每次 `run` 都现读磁盘。

## 验证结果

| 状态 | 验收项 | 备注 |
|---|---|---|
| 不检查 | §1 `scripts/typecheck.sh` 输出 `typecheck: OK` | |
| 通过 | §2 运行前确认可写区所在区块处于**未加载**状态 | |
| 通过 | §3 `snapshot_unloaded` 运行结果为 `PASSED` | |
| | §4 跑完后 `(2000,64,2000)` 的箱子与 5 个红石被原样还原（不是空气 / 石头） | 测试案例里面没有写箱子和红石 |
| | §5 跑完后 `(2000,64,2000)` 所在区块不再被强制加载 | |
| | §6 原有 `cleanup_snapshot_demo`（`forceChunks: true`）仍通过，箱子与 NBT 原样还原 | |
| 通过 | §7 三份掉落物（两个可写区内 + 间隙）在运行前都存在 | |
| 通过 | §8 运行中两个可写区所在区块被强制加载，**间隙区块未被强制加载** | |
| 通过 | §9 跑完后间隙物品仍存在，两个可写区内的物品已被清除 | |
| 通过 | §10 跑完后两个可写区所在区块的强制加载已取消 | |
| 通过 | §11 单可写区的 `lever_toggle` 仍通过（逐区域路径的回归） | |
| 通过 | §12 `roundtrip` 无「不等」，`runall` 结果与改动前一致 | |
| 通过 | §13 `snapshot` + `forceChunks=false` + 未加载区块 → 启动被拒绝，提示未加载的区块坐标 | |
| 通过 | §14 拒绝后重新加载区块，箱子与 5 个红石仍在（测试没有碰世界） | |
| 通过 | §15 把 `forceChunks` 改为 true → 运行 `PASSED`，箱子与 NBT 原样还原 | 没有给forceChunks做ui |
| 通过 | §16 `forceChunks=true` 跑完后区块不再被强制加载 | |
| 通过 | §17 多人服：`/wrutils sel` 圈出的范围被编辑器「新建」采用（不是玩家脚下） | |
| 通过 | §18 P5：定义 `dimension` 与执行世界不一致 → 启动被拒绝 | 需要考虑测试信号在两个甚至三个维度的情况 |
| 通过 | §19 P6：`lever_toggle_fail` 报告的 `step#` 是 steps 下标 1（不是 3） | |
| 通过 | §20 P7a：`/wrutils report last` 输出最新报告路径与正文 | |
| 通过 | §21 P7b：`/wrutils test delete <id>` 删除测试文件；再查同 id 报找不到 | 在打命令时，id能够快速匹配提示 |
| 通过 | §22 P7b：编辑器列表「删除」→ 确认框；确认后文件消失，取消则文件仍在 | |
| 通过 | §23 U4：命令 id 参数支持 Tab 补全（`show` / `run` / `delete` / `roundtrip`） | |

建议（将这些建议先添加到代办，暂不现实）：

1. 创建的steps 没有按照tick顺序进行排序，还有，不能确定同一tick下的place和assert的运行先后顺序。
2. 测试编辑器的测试列表可以选择排序方式，比如名称，创建时间，等。

## 怎么测

### 准备

1. 重启游戏（本次改了 Java）。
2. 把下面两个示例**手工拷贝**进存档：
   - `docs/examples/snapshot_unloaded.json`
   - `docs/examples/multi_input_gap.json`

   目标目录：`run/saves/<世界名>/wrutils1_21_11/tests/`
   （跑服务端则是 `run-server/world/wrutils1_21_11/tests/`）。示例不是软链，新增文件必须重拷。

### §1 编译

```bash
scripts/typecheck.sh
```

预期：`typecheck: OK（/tmp/wrt-out）`。

## 修复 1（历史，已被 §13 取代）：snapshot 自动加载区块（§2–§6）

> ⚠️ 2026-10-17 决策变更后，`snapshot` + `forceChunks=false` **不再自动加载**，而是拒绝执行。
> 下面 §2–§5 描述的是前一版行为，**不要再按此验收**；§6 的 `forceChunks=true` 回归仍然有效；
> 当前未加载语义见 §13–§16。

### §2 构造「区块未加载」场景

`snapshot_unloaded.json` 的 `origin` 是 `(2000,64,2000)`，可写区只有相对 `(0,0,0)` 一格，
`cleanup: snapshot`、`forceChunks: false`。

1. 先把区块加载起来，在可写区放一个带 NBT 的箱子：

```
/tp @s 2000 100 2000
/setblock 2000 64 2000 minecraft:chest{Items:[{Slot:0b,id:"minecraft:redstone",count:5}]}
```

2. 传送走，等待区块卸载（约 10 秒）：

```
/tp @s 0 100 0
```

3. 确认区块确实已卸载：

```
/execute if loaded 2000 64 2000 run say CHUNK_STILL_LOADED
```

预期：**没有** `CHUNK_STILL_LOADED` 输出。若出现，说明区块还在加载中，再等几秒或
传送得更远后重试；否则本次测试没有真正覆盖「未加载区块」的场景。

### §3 运行

```
/wrutils test run snapshot_unloaded
```

预期：`PASSED`。测试在 tick 2 把箱子换成石头，并断言石头。

### §4 检查还原

传送回去检查（区块重新加载后 `/data get block` 也可用）：

```
/tp @s 2000 100 2000
/data get block 2000 64 2000
```

预期：方块仍是 `minecraft:chest`，`Items` 里仍是 5 个红石——快照在测试前拍到了箱子，
跑完精确写回。

**若修复回退**，这里会是 `minecraft:air`（或测试留下的石头）：未加载区块被快照成空气，
`restore()` 会把可写区清成空气。

### §5 隐式强制加载已清理

运行前先确认没有别人强制加载这个区块：

```
/forceload query 2000 64 2000
```

预期：未被强制加载。跑完 §3 后再查一次，仍应是未被强制加载——
`snapshot` 模式虽然自动加载了区块，但 `restore()` 必须把它取消。

### §6 原有 snapshot 路径回归

```
/wrutils test run cleanup_snapshot_demo
```

预期：`PASSED`；按原手册 §11–§12 检查 `(101,63,200)` 的箱子与 NBT 原样还原、
`(100,63,200)` 回到测试前状态。这条确认「snapshot 自动加载」没有破坏 `forceChunks: true`
的原路径。

## 修复 2：多可写区逐区域处理（§7–§11）

`multi_input_gap.json` 的两个可写区是相对 `(0,0,0)` 与 `(40,0,0)`（绝对
`(100,63,200)` 与 `(140,63,200)`），中间隔着一个区块（chunk 7，x=112..127）。
`cleanup: none`、`forceChunks: true`、`clearEntities: true`；步骤放在 tick 200/201，
给手动查询留出约 10 秒。

### §7 放置三份掉落物

```
/summon item 100.5 63 200.5 {Tags:["wrt_input"],Item:{id:"minecraft:redstone",count:1}}
/summon item 120.5 63 200.5 {Tags:["wrt_gap"],Item:{id:"minecraft:redstone",count:1}}
/summon item 140.5 63 200.5 {Tags:["wrt_input"],Item:{id:"minecraft:redstone",count:1}}
```

确认都存在：

```
/execute if entity @e[type=item,tag=wrt_input] run say INPUT_ITEMS=OK
/execute if entity @e[type=item,tag=wrt_gap] run say GAP_ITEM=OK
```

预期：两条都输出。

> `clearEntities` 只清 `ItemEntity` / `ExperienceOrbEntity`。物品要落在可写区的方块空间内
> （y=63），否则不在清理范围内。

### §8 运行并检查强制加载范围

```
/wrutils test run multi_input_gap
```

命令返回后**立即**（tick 200 之前）依次执行：

```
/forceload query 100 63 200
/forceload query 140 63 200
/forceload query 120 63 200
```

预期：

- 前两条：对应区块**已被强制加载**（chunk 6 与 chunk 8）；
- 第三条：间隙区块 **未被强制加载**（chunk 7）。

> 中文客户端显示「已被强制加载 / 未被强制加载」，英文为 `is force loaded` / `is not force loaded`。
> 若三条都显示已加载，说明修复回退成了并集包围盒。
> 区块坐标：`100>>4=6`、`140>>4=8`、`120>>4=7`，z 都是 `200>>4=12`。

### §9 检查清实体范围

等运行结束后（聊天栏出现 `PASSED`）：

```
/execute if entity @e[type=item,tag=wrt_gap] run say GAP_SURVIVED
/execute if entity @e[type=item,tag=wrt_input] run say INPUT_SURVIVED
```

预期：

- 输出 `GAP_SURVIVED`——间隙物品没有在清理范围内；
- **没有** `INPUT_SURVIVED` 输出——两个可写区内的物品被清掉了（正向对照，证明
  `clearEntities` 确实执行了）。

> 修复回退时，间隙物品也会被清除（旧代码按 `x=100..140` 的包围盒清实体），
> 因而不会出现 `GAP_SURVIVED`。

### §10 强制加载已取消

运行结束后再查一次：

```
/forceload query 100 63 200
/forceload query 140 63 200
```

预期：都显示未被强制加载。

### §11 单可写区回归

```
/wrutils test run lever_toggle
```

预期：`PASSED`。确认逐区域路径对单可写区没有引入行为变化。

## §12 总回归

```
/wrutils test roundtrip
/wrutils test runall
```

预期：

- `roundtrip` 的「不等」为 0；总数 = tests 目录里的 `.json` 文件数（拷入两份新示例后 +2）。
- `runall` 中各测试结果与改动前一致；`multi_input_gap` 应通过。
- `snapshot_unloaded` 在区块未加载时会按新语义**启动失败 / ABORTED**（这是预期，不是回归失败）。
  想让它通过，需站在测试区域附近，或把该文件的 `forceChunks` 设为 `true`。

## 行为变更：snapshot 未加载时拒绝执行（§13–§16）

> 2026-10-17 决策：`cleanup=snapshot` 且 `forceChunks=false` 时，不再隐式加载区块；
> 只要测试涉及的任一区块未加载，就在启动前拒绝执行，不碰世界。
> `forceChunks=true` 仍显式加载；`none` / `clear_input` 不变。

### §13 未加载时拒绝启动

1. 按 §2 的步骤把箱子放在 `(2000,64,2000)`，传送走并确认区块未加载。
2. 确认存档里的 `snapshot_unloaded.json` 是 `"forceChunks": false`
   （如果之前按 §15 改成了 `true`，先改回来）。
3. 运行：

```
/wrutils test run snapshot_unloaded
```

预期：**启动失败**，聊天栏显示类似：

```
[WRT] 启动失败: cleanup=snapshot 要求测试涉及的区块已加载，但以下区块未加载: (125,125)。请先靠近测试区域，或在 area 里设置 "forceChunks": true 让引擎加载。
```

（`(125,125)` 是 `2000>>4` 得到的区块坐标；你的坐标不同则按实际显示。）
且**不会**输出 `running ...`，也没有测试进度。

> 这是**启动时**检查，不会阻止测试期间区块被卸载。若测试点远离玩家、需要全程保持加载，
> 请设 `forceChunks: true`。

### §14 拒绝后世界未被改动

1. 传送回测试区域（这会重新加载区块）。
2. 检查箱子：

```
/data get block 2000 64 2000
```

预期：仍是 `minecraft:chest` 与 5 个红石——拒绝发生在 `captureSnapshot()` / `applyCleanup()`
之前，没有清空或修改任何方块。

### §15 `forceChunks: true` 时仍自动加载并还原

1. 把存档里的 `snapshot_unloaded.json` 的 `"forceChunks"` 改成 `true`（只改 JSON，不用重启）。
2. 按 §2 重新构造未加载场景（传送走、确认 `if loaded` 无输出）。
3. 运行：

```
/wrutils test run snapshot_unloaded
```

预期：`PASSED`。`forceChunks` 显式开启，引擎先加载区块再拍快照；
跑完按 §4 的方法检查，箱子与 NBT 应原样还原。

### §16 显式强制加载已清理

接 §15，跑完后查询：

```
/forceload query 2000 64 2000
```

预期：未被强制加载——`restore()` 在还原方块后取消本次强制加载。

## 追加：多人下编辑器「新建」取服务端选区（§17）

> 这一条需要**真正的多人服务端**（`run-server` + 客户端连接）。单人集成服务器里客户端与服务端
> 共用同一份静态 `SelectionManager`，测不出差异。

### §17 命令选区被编辑器采用

1. 客户端连上服务端，进入 overworld。
2. 用命令圈一块范围（`/wrutils sel pos1|pos2`；燧石左右键也可以，它同样会上报服务端）。
3. 按 **K** 打开编辑器 → 点「新建」。
4. 预期：编辑屏里的可写区与 `origin` 是刚才圈出的范围（`origin` 取 min 角），
   **不是**玩家脚下那一格。
5. 对照：不做任何选区，直接点「新建」→ 退回玩家脚下（与之前一致）。
6. （可选）两个玩家各自圈不同范围后分别新建 → 各用各的选区。

> 请求是异步的：点「新建」后要等服务端响应才打开编辑屏。若服务器没装 WRT 或请求失败，
> 会退回客户端本地选区，界面仍能打开。

## 追加：其余评审修复（§18–§20）

### §18 维度不匹配拒绝启动（P5）

1. 复制一份测试定义到存档（例如 `lever_toggle.json`），改成：
   - `"id": "dimension_mismatch"`
   - `"dimension": "minecraft:the_nether"`
2. 站在**主世界**运行：

```
/wrutils test run dimension_mismatch
```

预期：

```
[WRT] 启动失败: 维度不匹配：定义是 minecraft:the_nether，当前世界是 minecraft:overworld。请到对应维度再运行
```

且**不会**输出 `running ...`、没有测试进度。
3. `runall` 里该定义应记 `ABORTED`（启动失败），其余测试继续。

> 测完删掉这份临时定义，或把 `dimension` 改回 `minecraft:overworld`。

### §19 失败 `step#` 使用 steps 下标（P6）

1. 把 `docs/examples/lever_toggle_fail.json` 拷进存档。
2. 运行：

```
/wrutils test run lever_toggle_fail
```

预期：`FAILED`，失败信息里是 **`step#1`**（`steps` 列表第 2 条，0 起算），
而不是修复前的 `step#3`（旧实现把 setup 的 2 块也计入编号）。

> 该定义 `setup` 有 2 项，所以修复前后的差值正好是 2。若看到 `step#3`，说明 P6 没有生效。

### §20 `/wrutils report last`（P7a）

1. 先跑任意一个测试，产生一份新报告：

```
/wrutils test run lever_toggle
```

2. 执行：

```
/wrutils report last
```

预期：

- 第一行是 `[WRT] 最近报告: <存档>/wrutils1_21_11/reports/<文件名>.txt`；
- 随后逐行输出该报告正文（以 `=== WRT report ===` 开头）。
3. 再跑另一个测试后重复，应显示更新的那份报告。

> P3（窗口断言异常 → `ABORTED`）与 P7c（服务端线程守卫）目前是代码级验证：
> 游戏内构造异常路径成本较高，未列入本节。

## 追加：测试文件删除（§21–§22）

> 命令与 GUI 都会删**存档里的 JSON 文件**，属于不可撤销操作。测的时候用临时定义，
> 不要删常用示例。

### §21 命令删除（P7b）

1. 复制 `docs/examples/lever_toggle.json` 到存档的 `tests/` 目录，改名为 `delete_cmd.json`，
   并把里面的 `"id"` 改成 `delete_cmd`。
2. 删除：

```
/wrutils test delete delete_cmd
```

预期：`[WRT] 已删除 delete_cmd`。
3. 确认文件消失：

```
/wrutils test show delete_cmd
```

预期：`[WRT] 找不到测试定义: delete_cmd`。
4. 再执行一次 `/wrutils test delete delete_cmd` → 同样报找不到。
5. 非法 id（不含字母 / 数字 / 下划线 / 连字符）：

```
/wrutils test delete ../server.properties
```

预期：`[WRT] 非法的测试 id: "../server.properties"`，且文件不会被删除。

### §22 GUI 删除（P7b）

1. 再复制一份 `lever_toggle.json` 到存档，改名为 `delete_gui.json`，`"id"` 改成 `delete_gui`。
2. 按 **K** 打开编辑器 → 选中 `delete_gui` → 点「删除」。
3. 预期：弹出确认框，正文包含「确定删除 delete_gui 吗？这会删除存档里的 delete_gui.json，无法撤销。」
4. 点「取消」→ 回到列表，`delete_gui` 仍在。
5. 再点「删除」→ 点「删除」→ 列表刷新，`delete_gui` 消失；
   `/wrutils test list` 里也不再出现。
6. 多人服下同样操作：删除请求走服务端（`TestEditorOp.DELETE`）；
   非 op 玩家会被服务端权限校验拒绝，列表不会变化。

### §23 命令 id 自动补全（U4）

1. 输入 `/wrutils test delete `（末尾留空格）→ 按 **Tab**：
   应列出 tests 目录里的全部 id（按字母排序）。
2. 输入 `/wrutils test delete lever` → 按 Tab：
   只补全以 `lever` 开头的 id（如 `lever_toggle`、`lever_toggle_fail`）。
3. 大小写不敏感：输入 `LEVER` 也应匹配。
4. 对 `/wrutils test show `、`/wrutils test run `、`/wrutils test roundtrip ` 重复，行为一致。

## 测完清理（可选）

```
/kill @e[type=item,tag=wrt_gap]
/setblock 100 63 200 minecraft:air
/setblock 140 63 200 minecraft:air
/setblock 2000 64 2000 minecraft:air
```

`multi_input_gap` 用的是 `cleanup: none`，跑完会把石头留在两个可写区；
`snapshot_unloaded` 会把箱子还原到 `(2000,64,2000)`，不清理也可以。

## 失败时看哪里

- 编译：`scripts/typecheck.sh` 的 javac 输出。
- 场景没生效：§2 出现 `CHUNK_STILL_LOADED` → 区块仍加载，测试没有覆盖到修复点。
- §4 箱子没了 / 变成空气：说明快照没拍到方块。检查 `WorldPrep.prepare()` 中
  `forceChunks()` 是否在 `captureSnapshot()` 之前、且 `snapshot` 模式无条件执行。
- §8 间隙区块也被强制加载：检查 `TestRun.chunkRegions` 是否又退回了并集包围盒。
- §9 输入区物品还在：确认 JSON 里 `clearEntities: true`；物品是否真的位于可写区方块空间内。
- §10 跑完仍被强制加载：检查 `WorldPrep.restore()` 是否遍历 `forcedChunks` 取消加载。
- 运行失败原因：报告在 `run/saves/<世界名>/wrutils1_21_11/reports/`（服务端为
  `run-server/world/wrutils1_21_11/reports/`）的 `.txt` / `.json`；
  聊天栏与 `latest.log` 里的 `[WRT]` 行也会给出准备 / 还原错误。
