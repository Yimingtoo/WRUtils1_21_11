# Phase 6 代码实现解释（燧石选区 / 进度 HUD / 多人服务器支持）

配套：[Phase 6 验证手册](Phase6验证手册.md)、[方案总纲](../redstone-unit-testing-plan.md)。
**状态**：三项全部落地并游戏内实测通过（§1–§35，2026-10-17）；`/tick sprint` 按方案标"可选"**未做**，Phase 6 以此收尾。

---

## 一、逐文件职责

### main / 网络（`net/`，全部新增）

| 文件 | 职责 |
|---|---|
| `WrtNetworking.java` | **两端**注册 4 个 payload 类型（C2S 2 个、S2C 2 个）。接收器不在这里——两侧各注册自己那半边，避免客户端加载服务端类 |
| `TestEditorOp.java` | `LIST / READ / WRITE` 三态枚举，请求与响应共用；编码是 `STRING.xmap(valueOf, name)` |
| `TestEditorRequest.java` | C2S：`requestId / op / id / json`。三种操作**合成一个包**——它们在服务端共用同一段"权限 + id 白名单"校验，拆开就得抄三遍 |
| `TestEditorResponse.java` | S2C：`requestId / op / ids / id / json / error`。按 `op` 取用对应字段，其余给空值；`error` 非空即失败 |
| `TestStatusUpdate.java` | S2C：`running + progress?`。手写 codec，`progress` 用**显式布尔前缀**表示有无，不靠"全零即 null" |
| `SelectionUpdate.java` | C2S：`pos1(boolean) + BlockPos`，燧石选点上报 |
| `TestEditorServer.java` | 服务端接收器：LIST / READ / WRITE + 选区上报；两道校验（见下） |

> **2026-10-17 追加（P7b / P4 / U4）**：`TestEditorOp` 现为
> `LIST / READ / WRITE / DELETE / SELECTION`；`ClientTestEditor` 相应增加 `deleteTest` / `selection`；
> `TestEditorServer` 增加 DELETE（删文件）与 SELECTION（读服务端选区）分支；
> 命令 id 参数增加 Tab 补全。上面的三态描述是 Phase 6 当时的状态。
| `TestStatusServer.java` | 每 tick 把引擎运行态推给全部玩家；**边沿触发**（空闲→空闲不发） |

### main / 其他

| 文件 | 改动 |
|---|---|
| `Wrutils1_21_11.java` | 初始化顺序：`WrtNetworking.registerPayloads()` → `TestEditorServer.register()` → `TestStatusServer.register()` → `WrtTestEngine.bootstrap()` |
| `test/runner/TestProgress.java`（**新增**） | 进度**不可变快照**：id / executed / totalSteps / asserts / failures / tick / timeoutTicks / queued |
| `test/WrtTestEngine.java` | `progress` 字段加 `volatile`，每 tick 由 `TestRun.progress(queued)` 整份替换；`isRunning()` 语义不变（单跑 / 批跑队列 / 录制探测任一为真） |
| `command/SelectionManager.java` | 代码未动，但产生了**两份副本**：客户端写本地那份画线框，服务端那份供 `/wrutils test new` 读 |

### client / 网络（`client/net/`，全部新增）

| 文件 | 职责 |
|---|---|
| `ClientTestEditor.java` | 发起 LIST / READ / WRITE，用自增 `requestId` 把响应配对回回调（`PENDING` 表）；回调一律切到客户端线程 |
| `ClientTestState.java` | 运行态**镜像**：整个 `TestStatusUpdate` 存在**一个** volatile 字段里；提供 `reset()` |

### client / 选区、HUD 与入口

| 文件 | 职责 |
|---|---|
| `client/selection/SelectionTool.java`（**新增**） | 燧石左键 pos1 / 右键 pos2；总开关（键位 **J**）；`active()` = 客户端侧 + 开关开 + 手持燧石 + **不在跑测试**；返回 `FAIL`（取消且不发包给服务端） |
| `client/selection/SelectionRenderer.java`（**新增**） | 把选区画成青色线框，挂 `BEFORE_DEBUG_RENDER` |
| `client/selection/SelectionHud.java`（**新增**） | 左下角两行提示，挂原版聊天层之后（继承 F1 隐藏） |
| `client/progress/ProgressHud.java`（**新增**） | 左上角进度；有失败整行标红；同样挂聊天层之后 |
| `client/Wrutils1_21_11Client.java` | 注册上述组件；**JOIN / DISCONNECT 复位会话**；开编辑器的闸改为"对面有没有装 WRT" |
| `client/editor/TestEditorScreen.java` | 列表 / 详情改成**异步**；新增 `listGeneration` 与"选中项是否还是它"两道过期响应闸 |
| `client/editor/TestEditScreen.java` | 保存改成异步；失败留在屏上报错；`close()` 前判 `currentScreen == this` |
| `client/editor/EditorSession.java` | **不再持有文件路径**；新增 `reset(id, dim, origin, inputs)` 供"新建复用选区" |

---

## 二、数据流走查

### 1. 选区（燧石 → 服务端）

```
左键/右键（客户端，SelectionTool.active）
  ├─ SelectionManager.setPos1/2(本地)      ← 线框立刻能画，不等网络
  └─ SelectionUpdate ──► 服务端 onSelection ──► SelectionManager.setPos1/2(服务端)
                                                  └─ /wrutils test new 读这份
```

两份副本的取舍：本地那份只为手感，**服务端那份才是权威**。玩家若改敲 `/wrutils sel pos1`，
本地线框不会跟着变——已知、有意。

### 2. 打开编辑器

```
按 K ──► ClientTestEditor.isAvailable()（ClientPlayNetworking.canSend）
      ──► ClientTestState.isRunning()（服务端推来的镜像）
      ──► TestEditorScreen.init ──► listTests()
              └─ requestId=n ──► C2S ──► 服务端（主线程）LIST 读 tests 目录
                                    └─► S2C ──► deliver() ──► PENDING.remove(n) ──► 回调
```

### 3. 读定义 / 存定义

```
点某个测试 ──► readTest(id) ──► 服务端 READ
                                  └─ load(file) ──► TestDefinitionWriter.toJson(...)   ← 走一遍 writer
                                        └─► 客户端拿到的永远是规范形状（相对坐标、省略可省字段）
点保存 ──► validateAll() → widenInputToFit() → session.json()
      ──► writeTest(id, json) ──► 服务端 WRITE
                                  ├─ TestDefinitionLoader.parse(json, id)   ← 先解析
                                  └─ TestDefinitionWriter.write(...)        ← 再原子落盘
                                        └─► 客户端：成功才提示「已保存」/ 关屏
```

**服务端读也走 writer 是刻意的**：否则客户端把文件原样存回时，形状差异会一路被"固化"下来。

### 4. 运行状态（服务端 → 全部客户端）

```
WrtTestEngine（服务端线程）
  └─ END_SERVER_TICK（整个服务器一次，不是每维度一次）
       ├─ 空闲且上次也空闲 → 直接 return（零流量）
       └─ 否则 → TestStatusUpdate(running, progress?) → 广播给所有玩家
                    ├─ 进度 HUD：读 progress（null 就不画）
                    └─ 编辑器互斥：读 running
```

**单人走同一条路**：编辑器不再直读 `WrtTestEngine`，也不直接碰文件系统。
代价是晚一个 tick；换来的是"单人与多人同一条码路"，不会出现"单人可以、多人坏"。

---

## 三、关键不变量

1. **客户端不碰服务端存档，也不直读引擎单例。** 所有文件操作与运行态都经 payload。
   这条替代了 Phase 5 的"同 JVM 直读"——那是本阶段**唯一被推翻**的旧假设。
2. **payload 类型两端注册、接收器各注册半边。** 漏注册类型 → 运行时 `IllegalArgumentException`；
   把服务端接收器写进 common → 客户端加载服务端类。
3. **响应必须按 `requestId` 配对**，不能依赖"一次只有一个请求"：连点两个测试就有两条在途 READ，
   谁先回来没有保证。
4. **运行态是一个 volatile 快照，不是两个字段。** 分两次写，读的人会撞见
   "running=true 但 progress 还是上一轮的"。
5. **镜像必须在连接边界复位。** 服务端边沿触发广播，"最后一条是 running=true 却断了线"的客户端
   会永久卡在测试运行中。JOIN / DISCONNECT 各复位一次；测试真在跑时每 tick 都广播，新客户端一 tick 内同步到真值。
6. **payload 是安全边界。** 这是本仓库第一次接收客户端输入，两道校验是新需求而非防御性编程：
   - `GAMEMASTERS_CHECK.allows(player.getPermissions())` 与 `/wrutils` 命令一致；
   - id 白名单 `[A-Za-z0-9_\-]+`——`"../../server.properties"` 是合法字符串，会逃出 tests 目录。
7. **选区有两份副本，服务端权威。** 本地副本只负责渲染。
8. **异步响应必须防"回错了屏"。** `listGeneration` 挡过期的 LIST，
   `ids.get(selected).equals(response.id())` 挡过期的 READ，`currentScreen == this` 挡晚到的 `close()` / `setScreen()`。
9. **省流量靠边沿触发，前提是状态能被复位。** 见第 5 条——两者是一套机制的两半，不能只做一半。

---

## 四、验证基础设施

仓库仍**没有测试框架**，本阶段的验证分三层：

- `scripts/typecheck.sh`：唯一的自动编译验证（不校验资源 / refmap / 运行时行为）；
- `/wrutils test roundtrip`：本阶段改了"谁写文件"，这条能把 IO 层回归按住；
- 游戏内验收：§1–§35，覆盖单人 / LAN / 独立服务器三种连接方式。

**一个必须记住的运行时假设**：集成服务器**暂停时仍处理网络包**。
`MinecraftServer.processPacketsAndTick` 里 `packetApplyBatcher.apply()` 是无条件执行的，
暂停只影响其后 `tick()` 的 keep-ticking 判断。单人下编辑器全靠它——若该假设失效，
表现为**打开编辑器永远停在「加载中…」**。

---

## 五、与方案的偏离 / 有意不做

| 项 | 说明 |
|---|---|
| payload 数量 | 计划写"约 5 个（含删除）"；实际 **4 个**。编辑器没有删除测试文件的入口，删的类别砍掉（2026-10-17 P7b 已补上删除，但作为现有 `TestEditorOp.DELETE` 加入，payload 数量仍为 4） |
| 降级骨架 | **没抄** `replay/` 的 `RecordingBackends.select + Noop`：那套的前提是存在多个互斥后端，而 Fabric 网络只有一种机制且永远可用 |
| 单人保留直读 | **不做**。单人多人同一条码路，是用户拍板的本阶段最大取舍 |
| 选区上报的权限检查 | **不查**。它只往服务端按 UUID 的小表里写两个坐标，无安全影响；消费方 `/wrutils test new` 自己挂 gamemaster 门槛。查了反而两难：静默丢弃让非 op 的点击毫无反馈，回错误又每次点击刷屏 |
| `registerLarge` | 不用。Fabric 分包阈值 S2C 1 MB、**C2S 32767 字节**；JSON 是 KB 级，真超了 Fabric 也会自动拆包 |
| `/tick sprint` | 方案标"可选"，**跳过**，Phase 6 以此收尾 |

**验收后补的两处**（§24 / §31 备注引出来）：

1. **镜像复位**：`ClientTestState.reset()` + 连接事件（见不变量 5）。
2. **编辑器「新建」读燧石选区**：原先一律取玩家脚下，导致"圈好范围、点新建却建在脚底下"。
   改为有完整选区就用它的 min 角作 origin（与 `/wrutils test new` 同义），否则退回脚下。

---

## 六、已知未闭环

- **`SelectionHud` 不查 `isRunning()`**：它只对齐 `SelectionTool.active()` 的前两条（开关开 + 手持燧石），
  所以测试跑起来时 HUD 仍写「开启」而实际点不动。是否补第三条**待用户决定**。
- **选区两份副本可能漂移**：用 `/wrutils sel pos1` 改服务端那份时，客户端线框不更新。有意为之。
- **`ClientTestEditor.PENDING` 没有超时清理**：响应若永远不来（例如服务端在请求途中被关闭），
  该条目会一直留在 map 里。每个条目只是一个回调引用，实际影响可忽略，未处理。
- `cleanup` 的 `none` / `snapshot`、`withinTicks`、按钮元件验证——Phase 3/4 遗留，与本阶段无关。
