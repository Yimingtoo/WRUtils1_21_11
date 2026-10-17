# Phase 3 游戏内验证手册

## 验证结果

在「状态」列写 **通过** / **不通过** / **未测试**（也可留空）；**需要或问题写在「备注」列**（Claude 读这张表判定状态，不会替你改状态、备注或结论）。

| 状态 | 验收项 | 备注 |
|---|---|---|
| 未测试 | §1 `runall` 总表正确（7/9，两个故意失败） | |
| 未测试 | §2-A 越界被拒，且世界一格未动 | |
| 未测试 | §2-B 机器本体不被改动（手工机器跑前跑后对比） | |
| 未测试 | §2-C `forceChunks` 默认关（远处不强制应超时） | |
| 未测试 | §3 红石强度断言（`received` 15 ↔ 0） | |
| 未测试 | §4 中继器/比较器右键 + `strict` | |
| 未测试 | §5 箱子 NBT subset / exact | |
| 未测试 | §6 漏斗传输 | |
| 未测试 | §7 报告文件结构与可打开性 | |

配套文档：[Phase3代码实现解释.md](Phase3代码实现解释.md)、[非破坏性可写区调整.md](非破坏性可写区调整.md)、[方案总纲](../redstone-unit-testing-plan.md)。
前序：[Phase2验证手册.md](Phase2验证手册.md)（其隔离步骤已被下述非破坏性方案取代，勿照跑）。

Phase 3 的验收：**三个红石元件各一测试 / 箱子 NBT 子集与全等 / 拉杆旁红石粉 `received=15` 再 `0` / 报告文件可打开且结构正确 / `runall` 总表**；
外加本轮新增的**非破坏性可写区**验收：**越界被拒、机器本体不被改动、`forceChunks` 默认关**。

**改动的是 Java** → 验证前**必须重启游戏**（改 JSON 不用）。

---

## 0. 准备（schema 已变，务必先读）

**测试定义结构变了**：原来的 `isolate` 段已改名为 **`area`**，且不再有 `radius`/`clearArea`，改为：

```json
"area": {
  "input": { "min": [100,63,200], "max": [101,64,200] },   // 唯一允许被 place/use 改动的矩形
  "cleanup": "clear_input",                                 // 跑前清空可写区；none/snapshot 尚未实现
  "forceChunks": false,                                     // 默认关
  "clearEntities": false,
  "gameRules": { "random_tick_speed": 0 }
}
```

- **`place`/`use` 的坐标必须在 `input` 内**，否则启动即被拒绝。
- **`assert` 不受限**（只读），可指向机器任意位置。
- **旧字段 `radius`/`clearArea` 出现即报错**——老文件不会静默跑错，会明确报错。

**文件已替你拷进** `run/saves/New World/wrutils1_21_11/tests/`，共 9 个：

| 文件 | 测什么 |
|---|---|
| `lever_toggle` / `lever_toggle_fail` | 冒烟（后者**故意失败**） |
| `long_running` | gamerule 生命周期 |
| `redstone_dust_power` | 红石强度 `received` 15 ↔ 0 |
| `repeater_delay_toggle` / `comparator_mode_toggle` | 中继器/比较器右键 |
| `chest_nbt_subset` | 箱子 NBT subset 与 exact |
| `hopper_transfer` | 漏斗传输 + NBT |
| `input_boundary_violation` | **故意越界**：验证非破坏性守卫（应为启动即报错） |

> 示例用 `forceChunks: true` 是因为它们固定在 `(100,63,200)` 这类远处；你自己在**近处**测试时可删掉它。
> 若你**又改了** `docs/examples/`，记得手动重拷。

---

## 1. runall 冒烟：总表 + 两种"预期失败"

```
/wrutils test list
```
→ 9 个测试。

```
/wrutils test runall
```
→ 总表：

```
[WRT] runall -- 9 tests
  PASSED   chest_nbt_subset  (0 failed)
  PASSED   comparator_mode_toggle  (0 failed)
  PASSED   hopper_transfer  (0 failed)
  ABORTED  input_boundary_violation  (0 failed)
  PASSED   lever_toggle  (0 failed)
  FAILED   lever_toggle_fail  (1 failed)
  PASSED   long_running  (0 failed)
  PASSED   redstone_dust_power  (0 failed)
  PASSED   repeater_delay_toggle  (0 failed)
[WRT] runall: 7/9 passed
```

**判定**：**7/9**。两个"失败"都是设计如此：`lever_toggle_fail` 是断言写反（FAILED），`input_boundary_violation` 是越界守卫（启动即 ABORTED）。

> `long_running` 最后一个断言在 tick 600（约 30 秒），整批以分钟计，正常。

---

## 2. 非破坏性守卫（本轮核心）

### A. 越界必须被拒，且不碰世界

先确认越界点原本是空气：

```
/execute if block 105 64 200 minecraft:air run say 105,64,200 是空气
```

```
/wrutils test run input_boundary_violation
```
→ **预期：启动即报错**，形如：

```
[WRT] 启动失败: place 步骤 位置 (105,64,200) 在可写区 [100,64,200]..[100,64,200] 之外：只有输入区内的方块可以被修改
```

再查同一格：

```
/execute if block 105 64 200 minecraft:air run say 105,64,200 仍是空气
```

**判定**：报错出现**且**该格仍是空气（`input` 内的石头也不该出现——校验发生在清理之前）。

### B. 机器本体不被改动（手工机器）

这条最能说明新语义。

1. 自己搭一个小机器，例如：拉杆 → 红石粉 → 活塞（或中继器）。
2. 写一个测试：`area.input` **只圈住拉杆那一格**；`setup` 为空；`steps` 只 `use` 拉杆，再 `assert` 活塞/红石粉的状态。
3. 跑前后各看一眼机器：**除拉杆外，每个方块与跑前完全一致**（红石粉、活塞、支撑方块都在）。
4. 反复跑几次，机器依旧在。

**若机器消失**：说明 `input` 圈大了，把机器本体也包进了可写区并被 `clear_input` 清掉——缩小 `input` 即可。

> 对照实验：把 `input` 故意扩到覆盖整台机器 → 再跑 → 机器会被清掉重建。这就证明"只有 `input` 会被动"。

### C. `forceChunks` 默认关

新写的测试（或删掉示例里的 `forceChunks`）默认**不**强制加载。把 `input` 设到离玩家 200 格外：不加强制 → 应超时 `ABORTED`；加上 `"forceChunks": true` → 正常 `PASSED`。与 [Phase2验证手册](Phase2验证手册.md) §3 同法。

---

## 3. 红石强度断言（`redstone_dust_power`）

```
/wrutils test run redstone_dust_power
```
→ **预期 `PASSED -- 9 steps, 3 asserts, 0 failed`**（`steps` 计数**含 `setup`**：4 条 setup + 5 条 steps = 9；asserts = 3）。

- tick4：拉杆 `powered=true` 且红石粉 `received=15`；
- tick8（关掉后）：红石粉 `received=0`。

**若 tick4 的 received 不是 15**：确认红石粉在 `setup` 中且紧邻拉杆（本样例两者都在 `input` 内）。

> 直观对照：把 `"min":15,"max":15` 改成 `"min":0,"max":14` 重跑（JSON 改了不用重启）→ 应变 FAILED，`why` 为 `received: expected received in [0, 14], got 15`。

---

## 4. 方块状态 strict 与元件右键

```
/wrutils test run repeater_delay_toggle
/wrutils test run comparator_mode_toggle
```
→ 两个都 `PASSED`。中继器 tick3/7 看到 `delay=2/3`；比较器 tick3/7 看到 `mode=subtract/compare`。

**若"点了没反应"**：属方案风险 #1——中继器/比较器 `onUse` 与拉杆不同，可能受假玩家 GameMode 影响（风险 #7）。看 `logs/latest.log` 里 `BlockInteractor` 记录的 `ActionResult`。

**顺带验 strict**：给比较器 tick3 的 expect 加 `"strict": true`（期望串已写全）→ 应仍 PASSED；把 `mode` 属性去掉再开 strict → 应 FAILED。

---

## 5. 箱子 NBT（`chest_nbt_subset`）

```
/wrutils test run chest_nbt_subset
```
→ **预期 `PASSED`**（2 条 assert）：
- `subset`：期望只写 `Slot`/`id`，**省略 `count`**——嵌套复合体子集匹配，缺键不算错；
- `exact`：写全 `count:5`，全等通过。

**若失败**：
- `act` 有 `Items` 但数值不符 → 看 `why` 的路径（如 `/Items[0]/count`）。
- `act` 无 `Items` → `place` 的 NBT 写入没生效（查 `TestRun.placeBlock` 的 `NbtReadView.create` 段与 `ErrorReporter.Logging` 日志）。
- id/count 大小写 → 1.21.11 用 `id`/`count`（小写）。

**验 subset 与 exact 的差别**：给两条 expect 都加 `"nbtIgnore": []`（清空默认忽略表）→ `subset` 仍 PASSED（容忍实际多出的 `x/y/z/id/components`），`exact` 变 FAILED（`/components: unexpected key`）。

> **列表一律要求长度相等**（对齐原版 `/execute if data`）：`Items` 里物品必须全列，少列一个即 FAILED。

---

## 6. 漏斗传输（`hopper_transfer`）

```
/wrutils test run hopper_transfer
```
→ **预期 `PASSED`**（tick20 断言下方箱子已收到 1 个红石）。漏斗每 8 tick 推 1 个，只放 1 个避免数量竞态。

**若失败且箱子为空**：确认漏斗 `facing=down` 且在箱子正上方、`forceChunks` 已开（本样例为 true）、世界不在 `/tick freeze`。

---

## 7. 报告文件

跑完任意测试后：

```
run/saves/New World/wrutils1_21_11/reports/
```
应见成对的 `<时间戳>-<id>.json` 与 `.txt`。JSON 含 `state/origin/ticks/executed/asserts/failed/failures[]`；失败时每条含 `kind/step/tick/pos/expected/actual/why`。

**判定**：JSON 可解析；`.txt` 与聊天摘要一致；失败时 `.txt` 的 `[FAIL]` 段与聊天**逐字相同**（同源 `Failure.describe`）。

> **跑完 `runall` 应有 9 个 `-<id>.json`**（含启动失败的 `input_boundary_violation`，其 `state` 为 `ABORTED`、`failures[0].kind` 为 `startup`）。若少一个，说明启动失败路径没落盘。
> **失败的 `exp` 显示的是你写的那串**（如 `minecraft:lever[powered=false]`），**不是**补全默认属性后的状态——非严格模式只比写了的属性，补全显示会误导。

---

## 未覆盖 / 已知缺口

- **侦测器（observer）样例未做**：输出是 1 tick 脉冲，精确 tick 断言必然闪烁；需要方案风险 #4 的 `withinTicks`（未实现）。
- **`cleanup` 的 `none`/`snapshot` 未实现**：选用会明确报错（不会静默按 `clear_input` 跑）。
- **按钮**：方案风险 #1 里仍待单独验证的元件。

## 失败时看哪里

```bash
grep '\[WRT\]' run/logs/latest.log | tail -50
```

引擎内部异常只在日志里有完整堆栈，聊天只给摘要。报告在
`run/saves/<世界名>/wrutils1_21_11/reports/`。
