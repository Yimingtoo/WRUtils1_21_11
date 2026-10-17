# Phase 2 游戏内验证手册

> **⚠ 本文的隔离步骤（清区域/`isolate.radius`）已于 2026-10-13 被非破坏性方案取代，勿照跑。**
> 现行步骤见 [Phase3验证手册.md](Phase3验证手册.md) 与 [非破坏性可写区调整.md](非破坏性可写区调整.md)。
> 下文仅作历史参考；其中 `test show` 打印的 `isolate=` 现在显示为 `area=AreaSpec[...]`。

配套文档：[Phase2代码实现解释.md](Phase2代码实现解释.md)、[方案总纲](../redstone-unit-testing-plan.md)。

Phase 2 的验收标准是方案里那四条：**连跑 10 次一致 / 残留方块清干净 / 远离玩家仍能跑 / 中途退出不泄漏**。本手册把每条拆成可照抄的命令与判定依据。

**建议顺序**：0 冒烟 → 1 重复性 → 2 清区域 → 3 强制区块 → 4 gamerule 生命周期。
第 4 步放最后——一旦发现 gamerule 泄漏，前三步的结论仍然可信；反过来则不然（泄漏的 gamerule 会污染后续所有测试）。

---

## 准备

**1. 放文件。** 两条路选一条：

- **自动**：游戏里 `/wrutils test new <id>` 生成骨架（需先选区），再手动编辑；
- **手动**：把仓库 `docs/examples/*.json` 拷到存档的测试目录：

```
<客户端运行目录>/saves/<世界名>/wrutils1_21_11/tests/
```

IDE 启动时运行目录就是仓库的 `run/`，即 `run/saves/<世界名>/wrutils1_21_11/tests/`。目录不存在时 `test new` 会自动创建。

**2. 确认权限。** `/wrutils` 需要 OP 2，单人存档房主默认满足；提示权限不足时先 `/op <你的名字>`。

**3. 定坐标。** 示例用的是 `origin = [100, 64, 200]`，**建议改成你附近的地方**——虽然 Phase 2 有强制区块，但改到近处方便肉眼观察。需同步改文件里的 `origin` 与 `setup`/`steps` 的 `pos`。

---

## 0. 冒烟：先确认命令都在

```
/wrutils test list
```
→ `[WRT] 3 个测试: lever_toggle, lever_toggle_fail, long_running`

```
/wrutils test show lever_toggle
```
→ 打印 dimension / origin / timeout、`isolate=IsolationSpec[radius=2, ... gameRules={random_tick_speed=0, spawn_mobs=false}]`、`setup=2 项, steps=2 项`，随后两行 `#0 t2 use ...`、`#1 t3 assert ...`

```
/wrutils sel pos1 100 64 200
/wrutils sel pos2 102 66 202
/wrutils sel info
```
→ `体积=3x3x3`

```
/wrutils test new smoke_test 冒烟测试
```
→ 生成文件，提示 `origin=(100, 64, 200) radius=2`

```
/wrutils test list
```
→ `smoke_test` 出现在列表里

> 这五条都正常就说明命令层没问题。验完可删掉 `smoke_test`（目前没有 `delete` 命令，手动删文件）。

---

## 1. 连跑 10 次结果一致

**在验证什么**：隔离生效后，同一测试不受上一次残留影响。

在聊天里敲：

```
/wrutils test run lever_toggle
```

**必须等它报完**再敲下一次（否则 `currentRun` 还在，会回"已有测试在运行"）。每次预期两行：

```
[WRT] running lever_toggle in minecraft:overworld
[WRT] lever_toggle PASSED -- 4 steps, 1 asserts, 0 failed
```

重复 10 次。

**判定**：10 次全部是 `PASSED -- 4 steps, 1 asserts, 0 failed`。

**为什么不是 2 次就够**：第二次跑时测试区里已经有个 `powered=true` 的拉杆。如果 `clearArea` 没生效，`setup` 放下的 `powered=false` 拉杆可能被残留的红石信号影响——单次通过排除不了这种"上一次恰好帮了下一次"。

---

### 踩坑记录：gamerule 在 1.21.11 被大批改名（2026-10-12，已修复）

首次运行时抛：

```
java.lang.IllegalArgumentException: unknown gamerule: do_mob_spawning
    at ...WorldPrep.findRule(WorldPrep.java:137)
```

命令最终以原版的 `An unexpected error occurred trying to execute that command` 收场。

**根因**：1.21.11 重命名了一大批 gamerule，旧名在世界里已不存在。示例 JSON 沿用了旧名。实测对照表：

| 旧名 | 1.21.11 |
|---|---|
| `doMobSpawning` | `spawn_mobs` |
| `doMobLoot` | `mob_drops` |
| `doTileDrops` | `block_drops` |
| `doEntityDrops` | `entity_drops` |
| `doDaylightCycle` | `advance_time` |
| `doWeatherCycle` | `advance_weather` |
| `doImmediateRespawn` | `immediate_respawn` |
| `disableRaids` | `raids` |
| `announceAdvancements` | `show_advancement_messages` |
| `naturalRegeneration` | `natural_health_regeneration` |

注意 **`random_tick_speed` 恰好没变**——所以它先匹配成功并存下了原值，然后才轮到 `do_mob_spawning` 报错。这类"一半成功一半失败"的报错最容易误判。

**修复**（三处）：

1. 示例 JSON 全部改用 `spawn_mobs`；
2. `WorldPrep.findRule` 匹配失败时**列出全部可用名字**，不用再翻文档；
3. `WrtTestCommand.run` 捕获启动异常 → `sendError`，不再冒泡成原版提示。

> **写测试定义时**：gamerule 名字请以游戏内 `/gamerule ` 的 tab 补全为准，别照搬旧版教程。




---

## 2. 残留方块被清干净

**在验证什么**：`clearArea` 按 `origin ± radius` 正确清空了区域（边界**含**）。

radius=2，所以范围是 x 98–102 / y 62–66 / z 198–202。

**a) 往测试区丢干扰方块**

```
/setblock 101 64 200 minecraft:diamond_block
/setblock 102 66 202 minecraft:gold_block
```

（第二个在角上，用来确认边界是闭区间）

**b) 在范围外放对照**

```
/setblock 110 64 200 minecraft:bedrock
```

**c) 跑测试**

```
/wrutils test run lever_toggle
```

**d) 等 `PASSED` 后逐条检查**

```
/execute if block 101 64 200 minecraft:air run say 101,64,200 已清空
/execute if block 102 66 202 minecraft:air run say 102,66,202 已清空
/execute if block 110 64 200 minecraft:bedrock run say 范围外未被误清
```

**预期**：三条都打印各自的 `say`。

**若第 1、2 条没打印**：`clearArea` 没覆盖到边界 → 查 `WorldPrep.clearArea()` 的 min/max 计算。
**若第 3 条没打印**：清区域越界了。

---

## 3. 远离玩家 200 格仍能跑

**在验证什么**：`forceChunks` 真的把测试区区块强制加载了。

```
/tp @s 100 64 420
```

（距 origin 220 格 ≈ 14 区块，超出默认视距的 tick 半径）

```
/wrutils test run lever_toggle
```

**预期**：仍 `PASSED`。

**更严格的对照**：把文件里 `"forceChunks"` 改成 `false`，重复上述步骤 → 应**超时 ABORTED**。这个对照才能证明"是强制区块让它生效"，而不是碰巧。

**收尾**：验完把 `forceChunks` 改回 `true`，并 `tp` 回测试区——玩家不在附近时区块已卸载，`/execute if block` 查不到东西。

---

## 4. gamerule 生命周期 + 中途退出不泄漏

Phase 2 **最需要确认**的一条：`WorldPrep` 里 `(GameRule<Object>) rule` 那段类型转换**完全无法离线验证**。

### A. 确认 gamerule 真的被设上

先记录当前值（默认 **3**）：

```
/gamerule random_tick_speed
```

启动长测试：

```
/wrutils test run long_running
```

（最后一个 assert 在 tick 600，约 30 秒）

跑起来后立刻查：

```
/gamerule random_tick_speed
/gamerule spawn_mobs
```

→ **预期：`0` 和 `false`**

> 若这里仍是 3 / true：`applyGameRules` 没生效，问题在 `findRule`（名字匹配）或 `convert`（类型转换）。

等它报完 `PASSED`（**这一轮不要中途退出**），再查：

```
/gamerule random_tick_speed
```
→ **预期回到 3**

### B. 中途退出不泄漏

```
/wrutils test run long_running
```

看到 `[WRT] running long_running` 后**立刻退出存档**（Esc → 保存并退出），别等它跑完。

重进世界后查：

```
/gamerule random_tick_speed
/gamerule spawn_mobs
/forceload query 100 200
```

→ **预期：`3` / `true` / 该区块不在强制加载列表中**

> 若 gamerule 停在 `0`：`SERVER_STOPPING` 那条兜底没生效，改动被写进存档了——最坏情况。去查 `WrtTestEngine.onServerStopping()` 是否真被调用。

---

## 失败时看哪里

**聊天栏**是主输出。完整信息（含异常堆栈）在：

```
<客户端运行目录>/logs/latest.log
```

所有 `[WRT]` 前缀的行都来自引擎，与聊天同源：

```bash
grep '\[WRT\]' run/logs/latest.log | tail -50
```

引擎内部的异常（`prepare` 回滚、tick 崩溃）**只在日志里有完整堆栈**，聊天只显示一句摘要。
