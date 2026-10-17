# Phase 4 代码实现解释

本文对应方案 [redstone-unit-testing-plan.md](../redstone-unit-testing-plan.md) 的 **Phase 4（录制后端）**。
前序：[Phase3代码实现解释.md](Phase3代码实现解释.md)、[非破坏性可写区调整.md](非破坏性可写区调整.md)。

Phase 4 的目标：**失败的测试留下一份可用 ReplayMod/Flashback 打开的回放**，通过的什么都不留；
没装 ServerReplay 时降级为"照常跑、只是不录"。

## 一、新增与改动

| 文件 | 动作 | 职责 |
|---|---|---|
| `replay/RecordingTarget.java` | 新增 | 录制目标（标签/维度/中心区块/半径） |
| `replay/RecordingBackend.java` | 新增 | 后端接口 |
| `replay/RecordingSession.java` | 新增 | 会话接口 `stop(server, keep)` |
| `replay/NoopRecordingBackend.java` | 新增 | 降级实现 |
| `replay/ServerReplayBackend.java` | 新增 | 纯字符串命令实现（无编译期依赖） |
| `replay/RecordingBackends.java` | 新增 | 探测 + 选择 |
| `replay/RecordingConfig.java` | 新增 | 自建配置读取 |
| `replay/RecordingFiles.java` | 新增 | 按标签定位/清理 `.mcpr` |
| `test/model/TestDefinition.java` | 改 | 加 `record`（默认 true）+ `withRecord(...)` |
| `test/io/TestDefinitionLoader.java` | 改 | 解析 `record`（缺省 true） |
| `test/report/TestResult.java` | 改 | 加 `recording`（可空） |
| `test/report/ReportWriter.java` | 改 | 报告写出 `recording` |
| `test/runner/TestRun.java` | 改 | 起停录制、算录制范围 |
| `test/WrtTestEngine.java` | 改 | 选后端；清理重试队列；`recording test` 探测；停机保留 |
| `command/WrtTestCommand.java` | 改 | `run --record/--no-record`；`recording status|test` |
| `resources/fabric.mod.json` | 改 | `recommends: server-replay` |

## 二、接口

```java
interface RecordingBackend { String id(); boolean isAvailable(MinecraftServer); RecordingSession start(MinecraftServer, RecordingTarget); }
interface RecordingSession { String label(); void stop(MinecraftServer, boolean keep); Optional<Path> producedFile(); }
record RecordingTarget(String label, String dimension, int centerChunkX, int centerChunkZ, int radiusChunks) {}
```

**接口只走字符串命令**，所以没有 ServerReplay 的 jar 也能编译、能运行——降级由 `NoopRecordingBackend` 兜底。

## 三、ServerReplayBackend 的关键事实

- 用 `chunks` 模式（**不能**用 `players`：`FakePlayer` 不在 `PlayerManager` 里）。
- 命令源用 `server.getCommandSource().withSilent()`（OWNERS = OP 4，`getServer()` 非 null）。
  **不要**用 `CommandManager.createSource(...)`——那里面 `MinecraftServer` 是 null，`/replay` 会 NPE。
- 起：`replay start chunks around <cx> <cz> radius <r> in <dim> named <label>`。
- 停：`replay stop chunks named <label> [false]`——**通过就传 `false`**（即"不保存"），省得事后删文件、绕开落盘竞态。
- `parseAndExecute` 返回 void 且内部吞异常，所以**通过**这一路还加了一层兜底：延迟若干 tick 再 glob
  删除该标签下的 `.mcpr`（见"清理重试队列"）。
- 探测可用性：`FabricLoader.isModLoaded("server-replay")` **且**命令树根节点有 `replay`（两个都查）。

## 四、录制范围与外扩

范围取自 `TestRun.chunkRegion`（**可写区 ∪ 全部步骤坐标**）——即"测试真正涉及的地方"，
换算成最小方形区块区，半径取 `max(该跨度, recordingMinRadiusChunks) + recordingRadiusExtraChunks`。

- **`recordingRadiusExtraChunks`（默认 1）必须外扩**：客户端不渲染录制区域最外圈的区块，
  想看到 `radius=2` 的范围，实际要按 `radius=3` 录。
- **`recordingMinRadiusChunks`（默认 8）是实测补的**（2026-10-14）：只按测试区大小算，
  小测试（如拉杆那种 1 区块）会录成 3×3 区块，回放里出了那点范围就是虚空，
  看起来像"**视野内渲染距离非常近**"。给一个足够大的最小值即可。

**相机**：`chunks` 模式录出的回放 `selfId = -1`，在 ReplayMod 里是**自由相机**，初始位置不受本命令控制
（也不绑定测试区）。想让打开时就在机器旁，**跑测试前 `/tp` 到测试区**——录到的玩家位置即相机的落点。

**与 `forceChunks` 的关系**：ServerReplay 的区块录制"开始时会加载区块，之后不再强制加载"。
所以录制依赖区块持续加载——若 `area.forceChunks=false`，引擎会在开始时**warning**：
区块若被卸载，录制会中断。这不是错误，只是提醒。

## 五、配置

`<runDir>/config/wrutils1_21_11.json`（缺省时用默认值，**不自动创建文件**）：

```json
{
  "recordingEnabled": true,
  "recordingMinRadiusChunks": 8,
  "recordingRadiusExtraChunks": 1,
  "recordingPaddingSeconds": 1.0,
  "chunkRecordingPath": "recordings/chunks"
}
```

`chunkRecordingPath` 若是相对路径，按**游戏运行目录**解析（与 ServerReplay 的 `./` 同义），
用于定位产出文件与兜底删除。

**`recordingPaddingSeconds`（默认 1.0）**：测试**前后各**预留的时长，换算成 tick（20 tick = 1 秒）
同时用于前置与后置。**只在真的录制时生效**——没录制就不白白延时。

## 六、生命周期与兜底

| 时机 | 行为 |
|---|---|
| 服务器启动 | `RecordingBackends.select(...)` 探一次后端；选不到就 warn 一次并用 Noop |
| `TestRun` 构造（`prep.prepare()` **之后**） | 若 `record` 且配置开启 → 起录制（不把"清空可写区"这段录进去） |
| 前置预留（有录制时） | 起录制后先空转 `paddingTicks` 个世界 tick 再开始 tick 0；此间 `relativeTick` 不动 |
| 正常跑完（有录制时） | 步骤跑完后不立刻收尾，先空转 `paddingTicks` 个 tick 让片尾留住结果，再 `finish(DONE)` |
| 结束 | 通过 → `stop(keep=false)` + 排入延迟清理；失败 → `stop(keep=true)`，产出路径写进报告 |
| 启动失败（越界等） | 根本没起录制（校验在构造录制之前） |
| `SERVER_STOPPING` | 保留现场：`currentRun.abortRecording()` 与探测会话都按 `keep=true` 停 |

**预留期间的报文**：`leadTicksRemaining` / `postRollTicksRemaining` 只在 `shouldTick()` 为真时递减——
`/tick freeze` 下不会白白吃掉预留时间。两个预留都**不计入 `relativeTick`**，所以报告里的 `ticks` 仍是测试自身的长度。

**清理重试队列**：通过的录制请求了 `save=false`，但文件仍可能异步落盘。`TestRun` 会把该标签
排进引擎的清理队列，引擎每 tick 尝试 glob 删除，最多重试 5 次（`CLEANUP_RETRIES`）。

## 七、命令

```
/wrutils test run <id> [--record|--no-record]   # 覆盖该文件的 record 设置
/wrutils recording status                        # 后端/配置概览
/wrutils recording test                          # 在原地起 40 tick 短录制并保留，报告产出位置
```

`recording test` 是 Phase 4 的**验收探针**：它不跑任何测试，只验证"后端可用 + 能产出文件"。

## 八、关键不变量（在可写区调整的 18 条之后）

19. **`replay/` 不引入任何编译期依赖**——只有字符串命令与 FabricLoader 探测；无 ServerReplay 也能编译运行。
20. **通过不落盘、失败才保留**：通过走 `save=false` + 延迟删除双保险；失败保留且路径写进报告 `recording` 字段。
21. **录制起停都必须在服务端线程**（命令在服务端线程执行；引擎 tick 回调亦然）。
22. **录制失败绝不打断测试**：`ReportWriter.writeQuietly` 同理，录制相关异常只告警。
    （注：`start` 走的是被吞异常的 `parseAndExecute`，本就不抛。）
23. **前后预留只在有录制时生效**，且两段都**不计入 `relativeTick`**（报告里的 `ticks` 仍是测试自身长度），
    并且只在 `shouldTick()` 为真时递减（freeze 不吃预留）。
24. **`timeoutTicks` 只约束"步骤是否跑完"**：后置预留（此时已跑完）不受它影响——否则加长片尾会把通过的测试变成 ABORTED。
