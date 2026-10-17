# Phase 7 游戏内验证手册

> **本手册覆盖 7.1（`withinTicks`，§1–§10）、7.2（`cleanup` 的 `none`/`snapshot`，§11–§18）
> 与 7.3（编辑器入口 / 超时自动抬高 / 恢复选中项 / 返回自动保存 / 方块串尾部校验，§19–§27）。**
> 方案见[主方案 Phase 7](../redstone-unit-testing-plan.md)。
>
> ⚠️ 改了 Java 代码 → **必须重启游戏**；只改 JSON → 不用，引擎每次 `run` 都现读磁盘。

## 验证结果

| 状态 | 验收项 | 备注 |
|---|---|---|
| 无需检测 | §1 `scripts/typecheck.sh` 输出 `typecheck: OK` | |
| 通过 | §2 `/wrutils test roundtrip` 无「不等」条目（数量 = tests 目录里的文件数） | |
| 通过 | §3 `/wrutils test run observer_pulse` 通过 | 详情查看report日志 |
| 通过 | §4 把 `withinTicks` 负向对照：改成 **0** → 失败（无窗口说明）；改成 **1** → 失败，信息含「期望在 tick 2..3 内任一 tick 成立」 | 在点击assert编辑中，方块串为空时保存会报“选中项的方块串非法”，不点击对应的assert，方块串为空时不会报“选中项的方块串非法”，请检查是否正常。另外assert的方块串是否可以为空 |
| 通过 | §5 编辑器的 assert 条目「窗口」框显示该断言的当前值且可编辑；切到 place/use 时该框禁用并清空 | |
| 通过 | §6 在编辑器里改窗口值 → 保存 → 重开仍是新值，JSON 里出现 `withinTicks` | |
| 通过 | §7 旧定义（如 `lever_toggle`）行为不变，保存后的 JSON **不含** `withinTicks` 键 | |
| 通过 | §8 窗口不能绕过 `timeoutTicks`：故意造一个窗口超出超时的定义 → `ABORTED`，不卡死 | |
| 通过 | §9 `/wrutils test runall` 结果与本次改动前一致 | |
| 通过 | §10 选中一个**只有 NBT / 红石条件、方块串为空**的 assert → 保存不再报「选中项的方块串非法」 | |
| 通过 | §11 `cleanup: snapshot`：手动预置的箱子在测试把它换成石头后，跑完**原样还原**（含 5 个红石的 NBT） | |
| 通过 | §12 `snapshot` 还会把 setup 放进可写区的石头**清回空气**（快照 = 测试前状态，不是 setup 后状态） | |
| 通过 | §13 `cleanup: none`：同样流程跑完，箱子位置**仍是石头**（不还原） | |
| 通过 | §14 `cleanup: clear_input`：同样流程跑完也是石头（与旧行为一致） | |
| 通过 | §15 `snapshot` + 超时中止：箱子在 tick 2 被改掉，tick 3 超时 → 中止后仍然还原 | |
| 暂不测试 | §16 可写区体积超过 100 万格 → 明确报「可写区过大」，不 OOM、不卡死 | |
| 通过 | §17 `/wrutils test roundtrip` 仍无「不等」 | |
| 通过 | §18 `/wrutils test runall` 结果与之前一致 | |
| 通过 | §19 可写区屏「清理」按钮按 清空 → 保持 → 快照 → 清空 循环；返回再进 / 改区域后仍保持所选值 | |
| 通过 | §20 在编辑器里切换 cleanup → 保存 → JSON 的 `cleanup` 值正确，重开显示一致 | |
| 通过 | §21 超时自动抬高：把某步骤 tick 改成 400 → 保存后 `timeoutTicks` 变为 500，状态栏有提示 | |
| 通过 | §22 超时只升不降：手工把超时设为 900 → 保存后仍是 900 | |
| 通过 | §23 按 K 重新打开编辑器时恢复上次选中的测试；从编辑屏返回列表也保留 | 编辑区返回自动保存，并给提示 |
| 通过 | §24 载入 `cleanup: snapshot` 的定义，可写区屏的按钮显示「清理:快照」 | |
| 通过 | §25 返回列表（ESC / 按钮）自动保存：**列表屏顶部**显示「已保存」提示，列表打开且仍选中该测试 | 不要再聊弹框里提示，在编辑区返回界面显示 |
| 通过 | §26 校验失败时返回：弹「无法保存」，选「继续编辑」留在屏上且改动还在；选「丢弃离开」回列表，磁盘内容未变 | 方块串存在问题仍能校验通过，然后退出 |
| 通过 | §27 方块串**尾部带多余字符**（如 `minecraft:stone撒地方`）：编辑器标红，返回时被「无法保存」拦下，不能落盘 | |

几条建议：

1. 超时自动设置为程序时间+100gt
2. 每次按K键进入界面以及从编辑器界面返回时，维持前一次选择项目

> 这两条已在 7.3 实现，验收项见 §19–§24。

## 怎么测

### 准备

1. 重启游戏（本次改了 Java）。
2. 把 `docs/examples/observer_pulse.json` **手工拷贝**进存档：
   `run/saves/<世界名>/wrutils1_21_11/tests/`（或 `run-server/world/...`，取决于你跑哪一套）。
   示例不是软链，改了 / 新增了都要重拷。

### §1 编译

```bash
scripts/typecheck.sh
```
预期：`typecheck: OK（/tmp/wrt-out）`。

### §2 往返保真

游戏内执行：

```
/wrutils test roundtrip
```

预期：每条都是「相等」，「不等」为 0。数量等于 tests 目录里的 `.json` 文件数
（拷入新示例后应比之前多 1）。

> 新字段的读写在 loader / writer 两侧是对称的；旧文件没有 `withinTicks` → 读成 0 →
> writer 不写 → 再读仍是 0，所以往返仍相等。

### §3 侦测器样例通过

```
/wrutils test run observer_pulse
```

预期：`PASSED`。这是一个**1 tick 脉冲**：拉杆在 tick 2 被点击，侦测器随后输出一个 tick 的
15 级信号，红石粉只在那一瞬间有电。精确断言（`withinTicks: 0`）必然抓不到它。

> **实测时序（2026-10-17）**：脉冲在点击后 **+2 tick** 才落到红石粉上（tick 2 点击 → tick 4 有电）。
> 所以断言用 `withinTicks: 3`（窗口 2..5）留一格余量；末尾的"回到 0"断言放在 tick 10。
>
> 若失败，先看失败信息里的 `act`：
> - 一直是 0 → 侦测器朝向或输出侧不对（`facing=west` 表示它观察西边的拉杆，输出在西边的背面，即东侧）。
> - 偶尔成功 → 把 `withinTicks` 再加大 1。

### §4 负向对照（证明是新语义在起作用）

修改**存档里**的 `observer_pulse.json`（不是 `docs/examples/` 那份），两步都要做，
结论完全不同：

**A. 把 `"withinTicks": 3` 改成 `0`（或删掉这一行）** → 精确断言，只在 tick 2 采样一次。

```
/wrutils test run observer_pulse
```

预期：`FAILED`，失败信息是普通的红石失败，**不含**「期望在 tick … 内任一 tick 成立」：

```
  [FAIL] observer_pulse step#N tick=2
    exp  received=15
    act  0
```

**B. 改成 `1`（窗口 2..3，仍然太窄）** → 再跑一次：

预期：`FAILED`，这次的 `why` 末尾会带窗口说明：

```
  why  received: expected received=15, got 0（期望在 tick 2..3 内任一 tick 成立）
```

这条是本阶段的核心证据：同一个定义，只因为窗口不同，结论就不同。
（实测中这个场景正好发生过——存档里的值曾被编辑器改成 1，报告就是 B 的样子。）

> 测完把值改回 **3**，或重新拷一份 `docs/examples/observer_pulse.json` 覆盖。

### §5–§6 编辑器入口

1. 按 **K** 打开编辑器 → 选中 `observer_pulse` → 点「编辑」。
2. 在条目列表里选中 `assert` 那条 → 表单第一行的「窗口」框应显示 **3**，且可编辑。
3. 选中任意 `place` / `use` 条目 → 「窗口」框变灰并清空（只有 assert 有窗口语义）。
4. 回到 assert，把窗口改成 **5** → 点「保存」。
5. 关闭后重新打开该测试 → 窗口仍是 5；打开 JSON 确认出现 `"withinTicks": 5`。

### §7 旧定义不受影响

```
/wrutils test run lever_toggle
```

预期：`PASSED`（与之前一致）。在编辑器里打开它并保存，JSON 里**不应**多出 `withinTicks` 键
——writer 只在值大于 0 时写出。

### §8 窗口不能绕过超时

复制一份 `observer_pulse.json` 为 `observer_timeout.json`，改：

- `"id": "observer_timeout"`
- `"timeoutTicks": 3`
- 把窗口断言的 `withinTicks` 改成 `5`

运行：

```
/wrutils test run observer_timeout
```

预期：`ABORTED`（超时），**不是**一直跑下去。窗口结束时 relativeTick 已经超过 `timeoutTicks`，
超时判定照常生效。

> 测完可以把这份临时定义删掉。

### §9 回归

```
/wrutils test runall
```

预期：各测试结果与改动前一致（`observer_pulse` 若已拷入，会多一条通过）。

### §10 assert 的空方块串

**assert 的方块串是可以为空的**：只查 NBT 或红石时，本来就没有 block 条件。
`observer_pulse` 的两条断言都是这种情况（只有 `redstone`）。

此前编辑器有一处规则不一致：选中某个 assert 再保存时，会拿输入框里的空串去校验，
报「选中项的方块串非法」；而不选中它保存却能通过。**已修**（`TestEditScreen.validateAll`
对 assert 的空串放行，与遍历模型时的规则一致）。

测法：

1. 打开 `observer_pulse` → 编辑 → 选中任一 `assert` 条目（方块串框为空）。
2. 直接点「保存」。

预期：保存成功（`已保存 observer_pulse.json`），不再报错。

> 注意：三条条件（block / nbt / redstone）全空的 assert 目前是允许的，但它**永远通过**，
> 没有任何意义。这是既有行为，本次未改；需要的话可以单独加一条校验。

## 怎么测 · cleanup 的 `none` / `snapshot`（§11–§16）

### 准备

把 `docs/examples/cleanup_snapshot_demo.json` 拷进存档的 `tests/` 目录。它的可写区是
`(100,63,200)` 与 `(101,63,200)` 两格，测试会把**后一格**换成石头；前格由 setup 放一块石头。

**怎么切换三种模式**：目前没有 GUI 入口（7.3 才加），直接改**存档里**那份
`tests/cleanup_snapshot_demo.json` 的 `"cleanup"` 值即可：`clear_input` / `none` / `snapshot`。
引擎每次 `run` 都现读磁盘，**改完不用重启**。

**每轮开始前**，先在可写区里手动放一个带 NBT 的箱子（快照拍的是"测试前"的世界，
所以箱子必须在运行前就存在）：

```
/setblock 101 63 200 minecraft:chest{Items:[{Slot:0b,id:"minecraft:redstone",count:5}]}
```

### §11–§12 `snapshot`

1. 确认 JSON 里 `"cleanup": "snapshot"`。
2. `/wrutils test run cleanup_snapshot_demo` → 预期 `PASSED`。
3. 跑完后检查箱子：

   ```
   /data get block 101 63 200
   ```

   预期：方块仍是 `minecraft:chest`，`Items` 里仍是 5 个红石（NBT 一并还原）。

4. 检查 setup 放的那块石头：

   ```
   /data get block 100 63 200
   ```

   预期：**回到测试前的状态**（通常是 `minecraft:air`）。注意快照不等于 setup 后状态——
   setup 也是测试的一部分，跑完会被还原掉。

### §13 `none`

1. 把 JSON 里的 `"cleanup": "snapshot"` 改成 `"none"`。
2. 重新放一次箱子（上一步的快照跑已经把箱子还原了，这里先保证它存在）。
3. 运行 → 预期 `PASSED`。
4. 检查 `(101,63,200)`：预期是 **`minecraft:stone`**，箱子已经没了（`none` 不还原）。

### §14 `clear_input` 回归

1. 把 `"cleanup"` 改成 `"clear_input"`。
2. 重新放箱子，运行。
3. 跑完后同样应是石头——这是为了确认改动没有影响旧的清理模式。

### §15 中止时也还原

1. 改回 `"cleanup": "snapshot"`，把 `"timeoutTicks"` 改成 `2`，
   并把那条 assert 的 `"tick"` 从 `2` 改成 `10`（让 tick 2 换掉箱子后还有未完成的步骤）。
2. 重新放箱子，运行 → 预期 `ABORTED`（tick 3 判定超时）。
3. 检查 `(101,63,200)`：预期箱子与 5 个红石**已经回来**——中止路径同样走 `forceRestore()`。

### §16 体积上限

1. 改回正常值，把 `area.input` 改成 `{ "min": [0,0,0], "max": [200,50,200] }`（约 206 万格）。
2. 运行 → 预期直接在启动阶段报错，聊天栏/日志里有：

   ```
   cleanup=snapshot 的可写区过大（2060451 格，上限 1000000）：请缩小 area.input
   ```

   且不会先强制加载大片区块、不会 OOM。

### §17–§18 回归

```
/wrutils test roundtrip
/wrutils test runall
```

`roundtrip` 仍应「不等」为 0（拷入新示例后总数再 +1）；`runall` 结果与改动前一致。

## 怎么测 · 编辑器入口与默认值（§19–§24）

### §19 cleanup 循环按钮

1. 按 **K** → 选中任意测试 → 「编辑」→ 「可写区」。
2. 底部有「清理:清空」按钮，点一下依次变成「清理:保持」→「清理:快照」→「清理:清空」。
3. 选到「快照」后点「返回」回编辑屏，再进「可写区」→ 仍显示「快照」。
4. 再改一次区域坐标 → 按钮显示不变（cleanup 与区域列表分开保存）。

### §20 保存后落盘

1. 在可写区屏选一个模式 → 返回 → 「保存」。
2. 打开该测试的 JSON，`area.cleanup` 应是你选的那个（`clear_input` / `none` / `snapshot`）。
3. 关闭编辑器再打开该测试 → 可写区屏显示一致。

### §21 超时自动抬高

1. 打开 `observer_pulse` → 「编辑」→ 把某一步的 `tick` 改成 **400**。
2. 点「保存」。
3. 预期：状态栏显示 `已保存 observer_pulse.json（超时自动提高到 500）`；
   JSON 的 `timeoutTicks` 变为 **500**（400 + 100）。

> 这会改掉 `observer_pulse` 原来的 `timeoutTicks: 60`。测完重新拷一份
> `docs/examples/observer_pulse.json` 覆盖即可。

### §22 只升不降

1. 继续在 `observer_pulse` 里把「超时」框手工填成 **900**。
2. 保存 → 预期 JSON 里仍是 **900**（步骤最晚只到 400，自动值 500 < 900，不会把它降回去）。

### §23 恢复上次选中项

1. 按 K → 选中 `cleanup_snapshot_demo` → 按 **ESC** 关掉编辑器。
2. 再按 K → 预期列表**仍然选中** `cleanup_snapshot_demo`。
3. 进「编辑」→ 「返回列表」→ 仍选中它（这条原本就成立，一并确认）。

### §24 载入已有定义的模式

1. 打开当前 `cleanup` 为 `snapshot` 的 `cleanup_snapshot_demo`。
2. 进「可写区」→ 按钮应显示「清理:快照」。

### §25 返回自动保存

1. 按 K → 选中任意测试 → 「编辑」→ 改一个值（比如描述）。
2. 按 **ESC**（或点「返回列表」）。
3. 预期：聊天栏出现 `[WRT] 已保存 <id>.json`；直接回到列表，且该测试仍处于选中状态。
4. 重新打开该测试 → 刚才的改动仍在（已落盘）。

> 超时若被自动抬高，提示里会附「（超时自动提高到 N）」。

### §26 / §27 校验失败时返回（含"尾部多余字符"）

> **§26 不通过的根因（2026-10-17 已修）**：`BlockArgumentParser.block(..., String, ...)` 只解析**前缀**，
> `"minecraft:stone撒地方"` 会被当成 `minecraft:stone` 静默接受。现在所有方块串都走
> `test/BlockStringParser`：解析后检查 `StringReader` 是否读完，尾部有残留即报
> 「方块串尾部有多余字符」。编辑器校验、place 步骤、assert 的 block 条件三处共用。

1. **§26**：进编辑屏，把某个 `place`/`setup` 的方块串改成一个非法值（例如 `minecraft:not_a_block`）。
2. **§27**：再试一个"前缀合法、尾部有垃圾"的值，例如 `minecraft:stone撒地方`
   （这正是 §26 之前漏掉的情况）。
3. 两种情况下，输入框都应变红；按 ESC。
4. 预期：弹出「无法保存」确认框，正文是具体的校验错误（§27 会显示「方块串尾部有多余字符」）。
5. 点「继续编辑」→ 回到编辑屏，非法内容**还在**（不会丢）。
6. 再按 ESC → 这次点「丢弃离开」→ 回到列表；用文本编辑器打开磁盘上的 JSON，
   内容应仍是修改前的版本。

> §27 在**运行时**同样生效：即使绕过编辑器手写了一个尾部带垃圾的方块串，`place` 也会报
> `bad block string: ... 方块串尾部有多余字符`，而不是悄悄按前缀放置。

## 失败时看哪里

- 编译问题：`scripts/typecheck.sh` 的 javac 输出。
- 加载/解析问题：聊天栏 + `latest.log` 里的 `[WRT]` 行；`withinTicks` 为负数会直接报
  `withinTicks 不能为负`。
- 断言为什么失败：报告 `.txt` 里的 `exp` / `act` / `why` 三行。窗口失败的 `why` 末尾会带
  「期望在 tick A..B 内任一 tick 成立」，`tick=` 显示的是窗口末。
- 编辑器不显示窗口框：确认选中的是 `assert` 条目（`use`/`place`/`setup` 都是禁用的）。
