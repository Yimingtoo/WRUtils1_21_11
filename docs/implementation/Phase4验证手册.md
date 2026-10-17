# Phase 4 游戏内验证手册

## 验证结果

在「状态」列写 **通过** / **不通过** / **未测试**（也可留空）；**需要或问题写在「备注」列**（Claude 读这张表判定状态，不会替你改状态、备注或结论）。

| 状态 | 验收项 | 备注 |
|---|---|---|
| 通过 | §1 后端概览：`recording status` 显示 `server-replay` | |
| 通过 | §2 `recording test` 能产出 `.mcpr` | |
| 通过 | §3-A 通过不留；§3-B 失败保留（报告 JSON 含 `recording` 字段） | |
| 通过 | §4 ReplayMod 打开：能看到机器 / 四周不空 / 最外圈未裁 / 片头片尾各约 1s | |
| 通过 | §5 卸载 ServerReplay 后 `runall` 降级正常、报告齐全 | |
| 未测试 | §6 停机保留（可选项） | |

未单独确认、暂不入表：相机初始位置是否落在机器旁（`/tp` 那招）；是否出现过异常。

配套：[Phase4代码实现解释.md](Phase4代码实现解释.md)、[方案总纲](../redstone-unit-testing-plan.md)。

Phase 4 的验收（方案原文）：
1. javac（**不需要 ServerReplay jar**）——已由 `scripts/typecheck.sh` 覆盖；
2. 装 ServerReplay + Fabric Language Kotlin → `/wrutils recording test` 能生成 `.mcpr`，
   **拷进客户端 `./replay_recordings/` 用 ReplayMod 实际打开，确认能看到红石机器、且最外圈区块没被裁掉**；
3. 失败的测试留下 `.mcpr`、通过的什么都不留；
4. **卸载 ServerReplay 后再跑一遍全部测试**，验证 Noop 降级无异常且报告完整。

**改动的是 Java** → 验证前**必须重启游戏**。

---

## 0. 准备

1. **装 ServerReplay**（`3.2.8+1.21.11`）与它依赖的 **Fabric Language Kotlin**，进 `mods/`。
   只带 ServerReplay 不带 Kotlin 会加载失败。
2. **（可选）写配置** `<runDir>/config/wrutils1_21_11.json`：
   ```json
   { "recordingEnabled": true, "recordingMinRadiusChunks": 8, "recordingRadiusExtraChunks": 1,
     "recordingPaddingSeconds": 1.0, "chunkRecordingPath": "recordings/chunks" }
   ```
   不写就用这套默认值。`recordingMinRadiusChunks` 决定录制区域**至少**多大（区块）；
   调小会像早期那样"出一点范围就是虚空"。
3. **跑测试前 `/tp` 到测试区附近**：`chunks` 模式录出的回放是**自由相机**（`selfId=-1`），
   初始位置跟随录到的玩家位置。站在机器旁，打开回放就正好在机器旁。
3. 确认测试文件已拷进 `run/saves/New World/wrutils1_21_11/tests/`。
4. **哪些示例会录制**：只有 `redstone_dust_power`（通过，验证"不留"）与 `lever_toggle_fail`（失败，验证"保留"）
   是 `"record": true`；其余 6 个都写了 `"record": false`，让 `runall` 轻量（尤其 `long_running` 有 600 tick）。
   `input_boundary_violation` 不需要该字段——它在启动校验阶段就失败，根本轮不到录制。

---

## 1. 后端概览 - 通过

```
/wrutils recording status
```
→ **装了 ServerReplay**：
```
[WRT] recording
  enabled=true
  backend=server-replay
  radiusExtraChunks=1
  chunkRecordingRoot=<runDir>/recordings/chunks
```
**没装**：`backend=none`。

> `backend=none` 且你没主动关录制时，日志里应有一条 warn："未检测到可用的录制后端（需安装 server-replay）…"——
> 每个服务器生命周期**只 warn 一次**。

---

## 2. 探测：能产出 `.mcpr` - 通过

**先站到一个能看清东西的位置**（比如你搭的机器旁），然后：

```
/wrutils recording test
```
→ `[WRT] 录制探测已开始，约 40 tick 后自动停止并保留`，约 2 秒后：
```
[WRT] 录制探测完成: <runDir>/recordings/chunks/probe-<毫秒>/<文件>.mcpr
```

**判定**：
- 该路径下**确实有 `.mcpr` 文件**（第 4 步会真打开它）。
- 若报"无法开始录制探测…后端不可用（none）" → ServerReplay 没装好，回到 §0。

> 若"完成"提示里给的是**目录**而不是具体文件（异步落盘没赶上），进目录看即可。

---

## 3. 通过不留、失败才留 - 通过

### A. 通过 → 什么都不留

```
/wrutils test run redstone_dust_power
```
→ `PASSED`，且聊天里**没有** `[WRT] replay:` 行。

去 `run/recordings/chunks/` 看：**不应**留下这次测试的目录/文件。
（`save=false` 之外还有延迟删除兜底，最多 5 tick 内清干净。）

### B. 失败 → 留下并写进报告

```
/wrutils test run lever_toggle_fail
```
→ `FAILED`，并多一行：
```
[WRT] replay: <runDir>/recordings/chunks/lever_toggle_fail-<毫秒>/<文件>.mcpr
```

对应报告（`run/saves/New World/wrutils1_21_11/reports/`）的 JSON 里会多一个字段：
```json
"recording": "<同样的路径>"
```

**判定**：失败这次的文件**在**（或至少目录在），通过的**不在**。

---

## 4. 用 ReplayMod 真打开（关键） - 通过

把 §3-B 那个 `.mcpr` 拷进**客户端**的 `./replay_recordings/`（若不存在就新建），
在游戏里用 ReplayMod 打开它。

**要看三件事**：
1. **能看到红石机器**（拉杆、红石粉等），不是一片空白——证明 chunk 模式真的录到了内容。
2. **四周不是"一步就到底的虚空"**——证明 `recordingMinRadiusChunks=8` 生效。
   若发现出几格就空了，把该值调大（如 12）重录。
3. **最外圈区块没被裁掉**——证明 `radiusExtraChunks=1` 的外扩生效。
   若发现边缘缺一块，把它调成 `2` 再试。

> **相机**：`chunks` 模式的回放是**自由相机**（元数据 `selfId=-1`），打开时的位置跟随录到的玩家。
> §0 第 3 步让你先 `/tp` 到测试区，就是为了这一点。若仍不在机器旁，用 ReplayMod 的自由相机飞过去即可——
> 这是 ReplayMod 的常规用法，不是录制缺陷。

**还要看片头片尾**：回放开头应有约 **1 秒**"什么都还没发生"的静置，结尾也有约 1 秒"机器已跑完"的静置——
这就是 `recordingPaddingSeconds`（默认 1.0）。以 `lever_toggle_fail` 为例：片头拉杆还没被点，
片尾拉杆保持着最终状态。觉得不够就调大它重录。

> 项目的验收标准里这条是硬指标：**必须真打开看一眼**，只看文件存在不算过。

---

## 5. Noop 降级：卸载 ServerReplay  - 通过

1. 把 ServerReplay（和 Fabric Language Kotlin）从 `mods/` **移走**，重启游戏。
2. ```
   /wrutils recording status
   ```
   → `backend=none`。
3. ```
   /wrutils test runall
   ```
   → 全部照常跑完，总表照常出（预期仍是 **7/9**：`lever_toggle_fail` FAILED、`input_boundary_violation` ABORTED）。
   报告文件**照常齐全**，只是没有 `recording` 字段。

**判定**：无异常、无崩溃、报告完整。这就是"降级"的验收。

---

## 6. 停机保留（可选）- 未测试，暂不测试

跑一个**会失败且耗时**的测试（可临时把 `long_running` 的某个期望写反），在它还没结束时**退出存档**。

重进后看 `run/recordings/chunks/`：应有那次录制的文件/目录（`SERVER_STOPPING` 按 `keep=true` 停）。
同时确认 gamerule / 强制区块已还原（沿用 [Phase3验证手册](Phase3验证手册.md) 的手法）。

---

## 失败时看哪里

```bash
grep '\[WRT\]' run/logs/latest.log | tail -80
```

`[WRT]` 前缀的行包括：后端选择、开始录制、录制完成、报告写入失败等。
**ServerReplay 自身的日志**（命令是否被接受、写文件在哪）也在这个文件里，搜 `replay` / `ServerReplay`。

若 `/wrutils recording test` 报"后端不可用"但 `status` 显示 `server-replay`：
命令树探测与 `isModLoaded` 不一致，去看启动日志里 ServerReplay 是否加载成功（多半是缺 Kotlin）。

## 已知未闭环项

- `/replay stop ... <save?>` 的 **`save` 参数是否真的生效**仍未从原文确认（方案风险 #2）；
  §3-A 就是为它准备的实测。若发现 `save=false` 无效，兜底删除会补上——但请把结论回写进方案风险表。
