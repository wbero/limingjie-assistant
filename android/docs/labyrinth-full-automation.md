# 黎明界最终全自动流程设计与当前项目状态

> 目标：在用户一次完成必要授权并设置批量目标后，让助手无人值守地重复执行“刷开局 → 进入黎明界 → 完成整局 → 通关或达到失败上限 → 再刷开局 → 强制回标题 → 下一轮”，直到所有目标轮次完成。
>
> 本文以当前分支 `feature/approval-shell-recognition-team-plan`、HEAD `6ed2ecc` 及工作区未提交改动为基线。文中严格区分 **已存在/已验证能力** 与 **待实现的最终批处理能力**。
>
> 复核日期 2026-09-16。除 HEAD 外，工作区另有四批未提交改动：阶段二状态机去文案化、连结奖励链与容错保留、美空详情 OCR 收紧、强列绑定防误点。这些改动已影响第 14 节的结论。

## 1. 当前结论

2026-09-26 延迟初始选人修复（诊断包 190009）：从「挑战中」主页继续时，地图短暂出现后才弹出 0/3 初始选人。旧逻辑以节点会话已创建判定为途中选人，首区起点也被事件自由选角处理器接管。现以节点进度判断是否已离开首区起点：只访问起点的初始选人仍交还入口规划器；已进入后续节点或后续区域的商店/事件选人仍交给路线处理器。回归覆盖首区起点、首区后续节点与第二区起点。

2026-09-26 冷启动接管修复（1.0.14 诊断包 183318）：助手自动拉起游戏并导航到黎明界后，无障碍仍记录 `com.gsc.announcement.AnnouncementActivity`，单局启动闸门要求 MainActivity，因而返回 BLOCKED。现改为按目标包是否在前台判断，已在前台则接管识别且不重发启动 Intent；增加 B 服、渠道服接管与启动失败分支回归测试。日志最后一帧在会话重置决策前发布，因此主页截图仍带上一帧的 WAITING_LOGIN 消息，不能据此认定导航失败。

当前项目已经具备最终全自动所需的大部分底层能力：

- 一级难度黎明界单局流程已经可以在实机条件下完整跑通到结算。
- 已有 MediaProjection 截图前台服务、Accessibility 输入服务、黎明界入口/地图/节点/战斗/奖励/结算状态机。
- 已有网络侧刷开局 `LabyrinthRerollWorkflow`、`LabyrinthRerollService` 与 Room 检查点持久化。
- 已有“战斗连续 3 次失败后停止当前自动战斗并调用刷开局”的接线。
- 已有 `GameSessionResetPlanner`，已经定义了“结束当前会话 → 登录 → 主页 → 冒险 → 黎明界 → 下一轮”的外层重置阶段。
- 当前 MediaProjection 实现已经针对 Android 14+/15 的单 token / 单 VirtualDisplay 规则做了保护。

因此下一阶段的主要任务不是重写单局黎明界逻辑，而是增加一个真正的 **BatchController / 多局生命周期管理器**，把已经存在但彼此独立的单局执行、刷开局、会话失效回标题、重新进入、轮次统计串成一个可恢复的长期任务。

---

## 2. 最终用户体验

用户在正式开始前只做一次设置，例如：

```text
批量任务

美食殿堂：10 次
咲恋救济院：10 次
破晓之星：成功通关 5 次

难度：1
战斗失败上限：3 次
失败达到上限：放弃当前开局并重新刷取

[开始全自动]
```

启动时完成：

1. Accessibility 已启用并连接；
2. 游戏保持最终横屏方向；
3. 用户授权一次 MediaProjection；
4. 创建一次 `VirtualDisplay`；
5. 批量任务开始。

之后直到目标全部完成，不再要求用户参与。

最终目标不是“每局授权一次”，而是：

```text
一次屏幕授权 = 一整个批量任务
```

例如 10 + 10 + 5 共 25 个目标轮次，共用同一个截图会话。

---

## 3. 当前已有模块

### 3.1 屏幕捕获

实现：`automation/capture/MediaProjectionCaptureService.kt`

当前行为：

- 作为 `mediaProjection` 类型 Foreground Service 运行；
- 注册 `MediaProjection.Callback.onStop()`；
- 等待真实显示尺寸稳定后才第一次创建 `VirtualDisplay`；
- Android 14+ 不重复调用 `createVirtualDisplay()`；
- 输入始终发往真实默认显示器，而不是 MediaProjection 的虚拟显示器；
- 捕获尺寸变化时不会冒险继续识别，而是停止捕获并要求重新授权。

当前代码中特别重要的设计：

```text
Android 14+：同一个 MediaProjection token 不允许创建第二个 VirtualDisplay
MuMu：resize()/setSurface() 后可能保留旧镜像变换
=> 尺寸变化时 fail closed
```

因此最终批处理的硬约束是：

> 开始批量任务前就把游戏置于最终横屏尺寸，整个批次期间禁止改变模拟器分辨率、旋转屏幕或销毁 Capture Service。

### 3.2 输入执行

实现：`automation/accessibility/LandosolAccessibilityService.kt`

用于：

- 点击；
- 滑动；
- 全局操作；
- 判断前台包名；
- 驱动游戏 UI 自动化。

这套能力与 MediaProjection 生命周期独立，因此游戏 Activity 跳转、返回标题、游戏自身会话失效都不会天然导致 Accessibility 授权失效。

### 3.3 单局黎明界自动化

核心实现：`LabyrinthEntryRecognitionSession.kt` 及 `labyrinth/*` 识别、策略、路线、编组模块。

当前单局已经覆盖的主要阶段包括：

```text
标题/加载
→ 主页
→ 冒险
→ 黎明界
→ 公会/开局
→ 初始角色
→ 地图与节点
→ 普通战斗 / EX / BOSS
→ 角色奖励 / 事件 / 遗物 / 商店 / 连结
→ 最终结算
```

项目目前采用“识别 → 决策 → 动作 → 状态确认”的受限自动化原则。入口阶段允许有限兜底动作，进入黎明界主体后不应使用无限制盲点。

### 3.4 战斗失败策略

当前 `LabyrinthEntryRecognitionSession` 已实现：

- 战斗失败页识别；
- 自动重试计数；
- EX/BOSS 独立策略；
- 单队 Boss 失败后可切换多队，`teamIndex` 在 3 队以内递进（`coerceIn(1, 3)`）；
- `rerollAfterThreeBattleFailures` 开关；
- 第三次挑战失败时不点击战斗“结束”结算，而是调用 `finishFromPlannerAndRequestReroll()`；
- `rerollRequester` 已接入 `LabyrinthController.startAfterBattleFailureReroll(accountId)`。

当前逻辑已经形成了：

```text
战斗失败
→ 达到失败阈值
→ 停止当前 RecognitionSession
→ 请求网络侧刷开局
```

缺少的是刷取完成后的 **UI 会话恢复与下一轮自动重启**。

### 3.5 网络侧刷开局

核心：

- `LabyrinthRerollWorkflow.kt`
- `LabyrinthController.kt`
- `LabyrinthRerollService.kt`
- `LabyrinthRerollCheckpointStore`
- Room 表 `labyrinth_reroll_checkpoints`

现有能力包括：

- 指定公会；
- 指定难度；
- 路线筛选；
- Boss 条件；
- 最大尝试次数 / 刷到出；
- 退出现有服务端黎明界；
- enter/top/read 等 API 流程；
- 刷取结果检查点持久化；
- 后台 `dataSync` Foreground Service；
- Partial WakeLock；
- 战斗失败时可由单局 FSM 直接触发。

需要注意：`LabyrinthRerollService` 的注释明确说明它是 **network-only reroll service**：

```text
Never starts a capture session or replays an interrupted enter.
```

这是正确边界。最终设计不应该让它接管截图/点击，而应由外层 BatchController 在刷取成功后恢复 UI 流程。

### 3.6 会话重置规划器

已有：`automation/session/GameSessionResetPlanner.kt`

已经定义阶段：

```text
PENDING
TERMINATING
RELAUNCHING
WAITING_LOGIN
NAVIGATING_HOME
NAVIGATING_ADVENTURE
NAVIGATING_DAWN_REALM
READY_FOR_NEXT_ROUND
SESSION_BLOCKED
FAILED
```

并且已经特别考虑“通过服务端刷取导致客户端旧会话失效”的模式：

- 不一定杀游戏进程；
- 可以等待“重新连接/重新登录”弹窗或标题页；
- 标题页出现后进入正常登录流程。

这与最终需求高度一致，但目前仍需要把它真正接到批量控制器以及两类结束场景。

---

## 4. 最终批量状态机

建议新增独立的顶层状态机，例如：

```kotlin
enum class LabyrinthBatchStage {
    IDLE,
    PREPARING_CAPTURE,
    REROLLING,
    WAITING_REROLL_RESULT,
    INVALIDATING_OLD_CLIENT_SESSION,
    RETURNING_TO_TITLE,
    ENTERING_GAME,
    ENTERING_LABYRINTH,
    RUNNING_LABYRINTH,
    RUN_CLEARED,
    RUN_FAILED,
    RECORDING_RESULT,
    ADVANCING_TARGET,
    COMPLETED,
    PAUSED,
    FAILED,
}
```

职责边界：

```text
BatchController
│
├── 决定当前该刷哪个公会、还差几次
├── 发起 reroll
├── 等待 reroll 成功
├── 触发旧客户端会话失效
├── 驱动返回标题
├── 启动/恢复单局 RecognitionSession
├── 接收通关 / 失败终态
├── 记录结果
└── 判断继续还是完成

LabyrinthEntryRecognitionSession
└── 只负责“一局内部怎么打”

LabyrinthRerollWorkflow
└── 只负责“服务端怎么刷出一个开局”

GameSessionResetPlanner
└── 只负责“旧客户端状态怎么回到新一局入口”
```

不要继续把多局生命周期塞进 `LabyrinthEntryRecognitionSession`。当前该类已经非常大，外层批处理继续堆进去会让节点状态、战斗状态和批次状态互相污染。

---

## 5. 两种结束场景的正式流程

### 5.1 情况 A：正常通关

已知游戏行为：

1. 通关结算完成后回到黎明界主页；
2. 后台完成下一次刷开局后，客户端仍停留在上一局结束后的旧本地状态；
3. 此时如果直接点击“黎明界”，会先进入公会选择页；
4. 由于本地状态未经过网络刷新，点击任意公会才触发旧会话失效并返回标题；
5. 因此最终自动化 **禁止使用“重新点击黎明界 → 再点公会”作为刷新方法**。

推荐流程：

```text
识别最终通关
→ 完成结算链
→ 回到黎明界主页
→ BatchController 记录本轮 SUCCESS
→ 后台执行下一次 reroll
→ reroll 成功
→ 不点击“进入黎明界”
→ 点击底部“主页”或“冒险”标签
→ 借网络请求触发旧客户端会话失效
→ 等待“重新连接/重新登录”弹窗或标题页
→ 确认返回标题
→ 正常入口导航
→ 主页
→ 冒险
→ 黎明界
→ 使用新服务端开局
→ 开始下一轮
```

关键安全规则：

> 当 BatchController 处于 `INVALIDATING_OLD_CLIENT_SESSION` 时，黎明界主页的“进入黎明界”按钮必须被全局禁止。只能使用已批准的“主页/冒险”刷新动作。

否则会进入一个正常单局 FSM 从未设计过的“旧本地状态公会选择页”。

### 5.2 情况 B：战斗失败达到上限

当前代码已经能在第三次挑战失败后发起 reroll。

目标流程：

```text
识别 BATTLE_FAILED
→ 达到允许重试次数
→ 不点击“结束”进行失败结算
→ BatchController 记录本轮 FAILED_MAX_RETRY
→ 后台执行 reroll
→ reroll 成功
→ 客户端仍保持战斗失败页
→ 点击“重新挑战”或“退出”中的一个经过实机验证的安全按钮
→ 该按钮触发网络交互
→ 旧客户端会话失效
→ 返回标题
→ 正常入口导航
→ 新一轮
```

这里建议最终只保留 **一个** 实机验证过最稳定的按钮作为“session invalidation trigger”，不要长期同时保留“重试或退出任选”。

已定：触发按钮为「重新挑战」。失败页没有底栏，通关路径用的主页标签在此页不存在；「结束」会提交失败结算，排除。`SessionExpiryFrameTracker.battleFailureRetryTrigger` 提供当前帧识别到的按钮中心，`AnchorTriggerSessionExpiryTerminator` 在该页点它。批次拥有的单局到达重试上限时，无论策略里「刷开局」是否开启，都以 FAILED_MAX_RETRY 结束（`labyrinthBatchOwnedRunEndsAtRetryLimit`），批次随后 reroll → 失败页点「重新挑战」→ 失效弹窗 → 返回标题。

优先原则：

- 不会真正提交上一局失败结算；
- 必然发起网络请求；
- 被服务端新 enter 状态拒绝后必然进入重新登录/标题链；
- 坐标或识别锚点稳定。

---

## 6. 批量目标模型

不要只保存“总共还要跑 N 次”。需要保存按公会拆分的目标和完成口径。

推荐：

```kotlin
enum class BatchGoalMode {
    ATTEMPTS,       // 启动/完成一轮即计数，不要求通关
    CLEARS,         // 只有通关才计数
}

data class LabyrinthBatchGoal(
    val guildId: Int,
    val targetCount: Int,
    val mode: BatchGoalMode,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
)
```

这样可以准确表达：

```text
美食殿堂：开局 10 次
咲恋救济院：开局 10 次
破晓之星：通关 5 次
```

如果实际需求最终统一为“全部按通关次数计算”，仍然建议保留 `mode`，因为测试期很可能需要“只跑 N 个开局采样”的能力。

### 6.1 计数时机

必须只在明确终态提交：

```text
SUCCESS            -> clearCount +1；ATTEMPTS 也 +1
FAILED_MAX_RETRY   -> failedCount +1；ATTEMPTS +1；CLEARS 不 +1
ABORTED_UNKNOWN    -> 不计正常轮次，单独记录异常
USER_STOPPED       -> 不计
```

不要在“刷到新开局”时就把轮次算完成，否则应用或游戏在途中崩溃会造成统计虚高。

---

## 7. 批量检查点与崩溃恢复

当前项目已经有 reroll 检查点，但最终多局任务还需要独立 Batch checkpoint。

建议持久化：

```kotlin
data class LabyrinthBatchCheckpoint(
    val batchId: String,
    val accountId: Long,
    val goals: List<LabyrinthBatchGoal>,
    val activeGoalIndex: Int,
    val stage: LabyrinthBatchStage,
    val currentRunId: String?,
    val currentEnterId: Long?,
    val currentGuildId: Int?,
    val currentDifficulty: Int,
    val currentRunOutcome: RunOutcome?,
    val rerollCompletedForNextRun: Boolean,
    val clientSessionNeedsInvalidation: Boolean,
    val lastSessionInvalidationAction: String?,
    val updatedAt: Long,
)
```

每一个不可逆操作之前/之后都落盘，例如：

```text
准备 reroll
reroll 成功
准备触发旧会话失效
已经返回标题
开始一局
确认通关
确认失败上限
结果已计数
切换到下一目标
```

这样 App 进程如果异常退出，重新打开后至少能告诉用户：

```text
上次批量任务停在：reroll 已成功、客户端旧会话尚未失效
```

而不是猜测并重复 enter / retire。

注意：Android 15 下 MediaProjection session 随 App 进程死亡而结束，因此 **进程死亡后的自动恢复仍然需要用户重新授权屏幕捕获**。检查点用于恢复业务状态，不用于绕过系统授权。

---

## 8. Android 15 屏幕授权与生命周期

### 8.1 正确模型

最终批次必须这样运行：

```text
用户点击“开始全自动”
→ 获取一次 MediaProjection 授权
→ 启动/保持 MediaProjectionCaptureService
→ 创建一次 VirtualDisplay
→ 全部轮次共享
→ 批次全部结束
→ 才 stop projection / release display
```

### 8.2 批次过程中禁止

禁止：

- 每轮停止 Capture Service；
- 每轮重新请求 MediaProjection；
- 每轮 release 后使用同一个 token 第二次 `createVirtualDisplay()`；
- 改变模拟器分辨率；
- 横竖屏切换；
- 批处理中清理助手自身进程；
- “清后台”时误杀 Landosol Toolbox；
- 将 reroll service 的结束等同于 capture service 的结束。

### 8.3 可以发生

在 Capture Service 仍存活的情况下，以下事件不应结束整个批次：

- PCR Activity 跳转；
- PCR 回标题；
- PCR 登录/加载；
- PCR 自身进程被单独重启；
- 单局 RecognitionSession stop/start；
- `LabyrinthRerollService` 独立启动和结束。

### 8.4 当前代码的实际限制

`MediaProjectionCaptureService.reconfigureDisplayIfNeeded()` 当前发现捕获尺寸变化时会主动：

```text
stopCapture("...请保持游戏当前方向并重新授权屏幕捕获")
```

所以最终全自动必须在 UI 上明确提示：

> 开始前先进入与游戏相同的最终横屏尺寸；批量过程中不要旋转、调整模拟器分辨率或改变显示模式。

---

## 9. RecognitionSession 与 CaptureSession 必须解耦

这是最终实现最容易出错的地方。

当前 `finishFromPlannerAndRequestReroll()` 会：

```text
stop 当前 RecognitionSession
→ 调用 rerollRequester
```

这本身没有问题，但 **`stop()` 的语义目前同时结束了截图会话**。这一点已核实，不是待确认项：

```text
LabyrinthEntryRecognitionSession.stop()      // 会话源码
→ 释放 CaptureFrameBus 租约
→ requestCaptureStop()                        // 无条件调用，共 9 处调用点
→ captureStop()                               // LandosolToolboxApplication.kt:165 接线
→ MediaProjectionCaptureService.stop(this)
```

`finishFromPlannerAndRequestReroll()` 走的正是这条 `stop()`，所以现状恰好就是本节下方标注为“不能是”的那种形态：Run STOP 会连带 Capture STOP。批量模式下这意味着第二局必须重新授权屏幕捕获，与“一次授权 = 一个批量任务”的目标直接冲突。

**因此这是 Phase 1 的前置阻塞项，必须先解耦，再接入任何批量编排。** 第 15 节的 Phase 2 之所以“路径较短、适合先实机验证”，前提也正是先修掉这一条。

最终需要三层生命周期：

```text
Capture lifetime
└── 整个批量任务，最长

Batch lifetime
└── 10+10+5 等全部目标

Run lifetime
└── 一次黎明界开局，最短
```

期望关系：

```text
Capture START
  Batch START
    Run #1 START -> STOP
    reroll
    reset client
    Run #2 START -> STOP
    reroll
    ...
  Batch COMPLETE
Capture STOP
```

不能是：

```text
Run STOP
→ Capture STOP
→ 下一轮再次申请授权
```

---

## 10. 入口审批壳在批量模式中的角色

最终多局流程更需要审批壳，而不是更少。

### 10.1 正常登录阶段

允许页面集合应限制为：

```text
TITLE_WAITING_TAP
GAME_LOADING_PROGRESS
PRE_HOME_DATA_LOADING
公告/主页相关已知状态
HOME
ADVENTURE
DAWN_REALM_HOME_IDLE
```

只有入口阶段可以使用有限的兜底推进点击。

### 10.2 会话失效阶段

BatchController 明确进入 `RETURNING_TO_TITLE` 后，允许：

```text
旧页面
重新连接弹窗
重新登录弹窗
TITLE_WAITING_TAP
```

其它任何未知页面不得自动扩大点击范围。

### 10.3 单局运行阶段

继续沿用现有黎明界 FSM 的受限动作，不允许因为外层加入 BatchController 又恢复“UNKNOWN 页面随机点击”。

---

## 11. 通关终态必须成为正式事件

最终 BatchController 不应该通过 UI 文案或 RecognitionSession 是否 stop 来猜“是不是通关了”。

建议单局自动化向外只发明确事件：

```kotlin
sealed interface LabyrinthRunTerminalEvent {
    data class Cleared(...) : LabyrinthRunTerminalEvent
    data class FailedMaxRetry(...) : LabyrinthRunTerminalEvent
    data class FatalUnknown(val reason: String) : LabyrinthRunTerminalEvent
    data object UserStopped : LabyrinthRunTerminalEvent
}
```

其中 `Cleared` 必须在最终结算链完成、确认已经回到黎明界主页后再发。

这样可以避免：

- Boss 刚死亡就开始下一次 reroll；
- 角色奖励尚未领取就替换服务端状态；
- 结算动画中断；
- “完成路线”与“真正完成本局”混淆。

---

## 12. 下一轮公会选择

批量目标本身决定下一局 reroll 的公会。

例如：

```text
目标 1：美食殿堂 10
目标 2：咲恋救济院 10
目标 3：破晓之星通关 5
```

调度策略建议默认顺序执行：

```text
美食殿堂完成目标
→ 咲恋救济院完成目标
→ 破晓之星完成目标
```

而不是每局轮换公会。原因：

- 更容易排查识别/角色策略问题；
- opening roster policy 可在一批内保持稳定；
- 日志更容易比较；
- 减少频繁切换策略状态。

目标切换时必须重新调用现有：

```text
planner.configureOpeningRoster(guildId)
```

并清空上一局角色、节点、战斗、编组、pending transition 等所有 **Run-scoped** 状态。

---

## 13. 每轮开始前必须清空的状态

历史实机问题已经证明，跨局状态污染会造成非常隐蔽的错误。

下一轮开始时至少应重置：

- 当前路线执行位置；
- 当前节点/待确认节点；
- `pendingNodeTransition`；
- 已加入角色 roster；
- reward acquisition 状态；
- battle retry count；
- EX retry 状态；
- Boss single/multi fallback 状态；
- 当前战斗类型/context；
- 上一次队伍签名；
- 编组扫描游标；
- 有效角色扫描结果；
- shop/event 子流程状态；
- settlement 子流程状态；
- 滚动方向/边界证据；
- UNKNOWN 恢复计数；
- 本局 trace id。

但不得重置：

- MediaProjection；
- VirtualDisplay；
- Accessibility 连接；
- Batch checkpoint；
- 当前批量目标累计计数。

---

## 14. 与当前“节点状态遗漏”问题的关系

近期出现过：20601 已通关并领取角色后，下一节点已经是 20701，但系统仍继续寻找 20601。

截至 2026-09-16 已定位并修复三个独立根因：

```text
连结奖励链未被当作进入凭证
  连结只接受 LINK_CHOICE，而三个角色物品奖励页与角色奖励选择页未被接受；
  采样帧跳过短暂的连结选择页后，游标不再推进。已对齐事件节点的页面集合。

容错期间 pending 转移被页面切换清掉
  三帧类型容错的设计前提是下一帧仍能看到待确认转移，但语义页面立即清空了它；
  第二帧的正确页面已无转移可推进，票据恢复又因多后继被拒。已加入容错期保留。

语义修复把单个候选绑定到错误槽位
  完整三节点列被识别后，发光动画让下方节点短暂消失，剩下的单个候选框跨越第 1、2 行，
  语义修复只依据类型唯一性采纳了它，点击落在上方事件节点。已加入强列绑定记忆。

这类问题在单局模式下只是“卡当前局”，在无人值守多局模式下会直接破坏整个批次。

上述三例都已修复并入库为回归测试，但都是“补住已知破口”，**账本本身仍然缺失**。

2026-09-17 第一次完整跑到最终结算，又暴露出结算链自身的两个问题（与节点账本无关，记在这里便于对照第 11 节的 `Cleared` 前置条件）：

```text
RESULT 页无计划
  最终 Boss 完成路线时先进入 BEFORE_SCORE，随后 RESULT 页出现；
  旧转换只接受 NONE → SCORE_RESULT，于是 RESULT 页永远拿不到关闭按钮计划，
  402 次盲点全部落在动画兜底点上。已改为 NONE/BEFORE_SCORE 均可进入 SCORE_RESULT。
  实机复测（2026-09-17 02:08）又发现第一次点「关闭」只跳过分数滚动动画，RESULT 页仍在，
  而阶段已推进到 CHEST_SEQUENCE，RESULT 页再次无计划。已允许 CHEST_SEQUENCE 在 RESULT 页
  可见时退回 SCORE_RESULT；可见的 RESULT 页就是分数阶段，宝箱链在它消失前不算开始。

宝箱开封结果的确认按钮位置错
  该弹窗是居中模态，确认在底部居中 (958,958)；原兜底点和锚点都指向右下角的「下一步」。
  已按实机截图改锚点与兜底点。

宝箱开封结果与最终获得道具无法识别（2026-09-17 录像 公主连结(28).mp4 逐帧回放）
  宝箱页：run_clear/ 模板目录从未存在，页面只能分到 UNKNOWN。已从录像裁出
  run_clear_chest_result_title.png 与 run_clear_chest_confirm_button.png 入包，锚点框与裁剪对齐。
  获得道具页：地图的标题和两侧控件在弹窗后面仍可见，NODE_SELECTION 0.82 与 ITEM_REWARD 0.88 落在
  歧义边距内，页面判为 UNKNOWN，最终道具永远关不掉。分类器增加「强模态覆盖地图」规则：
  三个道具页锚点都 ≥0.80 时弹窗拥有该帧。
  录像 10 个关键帧已固化为回归夹具（test/resources/labyrinth/run-clear-20260917），
  用发布资源包逐帧断言页面。
```

所以在批量模式上线前，需要保证：

```text
进入节点
→ 建立节点语义凭证
→ 节点主体完成
→ 奖励/结算完成
→ commit 当前节点完成
→ route cursor 前进
→ 才允许寻找下一个节点
```

不能仅依赖当前视觉画面反推“之前节点是否完成”。

建议把“本局流程账本”作为 Run checkpoint 的一部分，至少记录：

```text
enteredNodeId
nodeOutcomeConfirmed
rewardCommitted
lastCompletedNodeId
nextExpectedNodeIds
```

BatchController 不直接操作这些字段，只要求单局 FSM 在发出 `Cleared` 前内部账本完全一致。

---

## 15. 推荐实现顺序

### Phase 1：只做外层编排，不动识别

新增：

- `LabyrinthBatchController`
- `LabyrinthBatchCheckpointStore`
- `LabyrinthBatchGoal`
- `LabyrinthRunTerminalEvent`

先使用手动/测试事件模拟：

```text
RUNNING
→ Cleared
→ REROLLING
→ RETURNING_TO_TITLE
→ NEXT_RUN
```

验证状态机本身。

### Phase 2：接通战斗失败路径

复用现有 `finishFromPlannerAndRequestReroll()`，改成把终态交给 BatchController，而不是单局 FSM 自己决定整个后续生命周期。

目标：

```text
第三次失败
→ reroll
→ 失败页触发会话失效
→ 标题
→ 新一轮自动开始
```

这一条比通关路径短，适合先实机验证。

### Phase 3：接通正常通关路径

增加可靠 `Cleared` 事件并实现：

```text
黎明界主页
→ reroll
→ 点击主页/冒险触发旧会话失效
→ 标题
→ 新一轮
```

特别测试：不得误点“进入黎明界”。

### Phase 4：批量目标与 UI

支持：

- 多个公会目标；
- ATTEMPTS / CLEARS；
- 目标完成数；
- 当前第几轮；
- 成功/失败/异常计数；
- 暂停；
- 停止；
- 崩溃后显示可恢复 checkpoint。

### Phase 5：长时间稳定性

至少执行：

```text
同一公会 10 轮
两个公会各 10 轮
混合成功/失败 20+ 轮
```

观察：

- Capture Service 是否始终存活；
- VirtualDisplay 是否从未重建；
- Accessibility 是否保持连接；
- 内存是否持续增长；
- Bitmap/ImageReader 是否泄漏；
- OCR worker 是否累积；
- 前台服务时限；
- WakeLock 生命周期；
- 每轮状态是否完全清空。

---

## 16. 必须补的测试

### 16.1 纯 JVM 状态机测试

覆盖：

1. `Cleared -> reroll -> invalidate -> title -> next run`；
2. `FailedMaxRetry -> reroll -> failure page invalidation -> title -> next run`；
3. reroll 失败不得点击客户端；
4. reroll 成功但未确认会话失效，不得启动下一轮；
5. 通关后的旧黎明界主页禁止点击“进入黎明界”；
6. 目标由公会 A 切换到公会 B 后，opening roster policy 同步更新；
7. CLEARS 模式失败不增加完成数；
8. ATTEMPTS 模式失败增加完成轮数；
9. 重启恢复 checkpoint 不重复计数；
10. 同一 run outcome 重复上报保持幂等。

### 16.2 Android/实机测试

至少验证：

1. Android 15 一次授权连续完成 3 局；
2. 三局期间 `createVirtualDisplay()` 只调用一次；
3. PCR 返回标题不影响截图；
4. PCR 被重启不影响截图；
5. `LabyrinthRerollService` 结束不停止 Capture Service；
6. RecognitionSession stop/start 不停止 Capture Service；
7. 通关后点主页/冒险可以稳定触发回标题；
8. 失败页选定的按钮可以稳定触发回标题；
9. 屏幕旋转/尺寸变化会明确暂停整个 Batch，并提示重新授权，而不是继续错误识别；
10. MediaProjection 被系统撤销时 Batch 转 `PAUSED/FAILED_CAPTURE`，绝不继续盲点。

---

## 17. 日志与调试包

最终无人值守模式必须给每一轮一个唯一 ID。

推荐日志前缀：

```text
batch=20260916-001 goal=gourmet run=007 stage=RUNNING ...
batch=20260916-001 goal=gourmet run=007 outcome=CLEARED
batch=20260916-001 goal=gourmet run=007 stage=REROLLING
batch=20260916-001 goal=gourmet run=008 stage=RETURNING_TO_TITLE
```

调试包建议额外导出：

- Batch checkpoint；
- 每轮 outcome；
- 每轮开始/结束时间；
- enterId；
- 公会、难度；
- 最后完成节点；
- 失败战斗类型；
- retry count；
- 触发 reroll 原因；
- session invalidation 使用的动作；
- 标题页重新出现耗时；
- Capture session startedAt 与是否发生 onStop。

这样以后出现“跑了 17 局，第 18 局莫名停了”，不需要依赖上一份已经丢失的 log 猜原因。

---

## 18. 安全停止条件

以下情况必须停止/暂停整个 Batch，不能自动扩大动作范围：

- MediaProjection `onStop()`；
- 捕获尺寸变化；
- Accessibility 断开；
- 当前前台包长期不是游戏；
- reroll API 返回未知不可恢复错误；
- 验证码；
- 回标题超时；
- 新一轮入口无法与 reroll checkpoint 对齐；
- 连续出现未知页面；
- run checkpoint 与视觉状态矛盾；
- 当前公会/难度与 Batch 目标不一致；
- 服务端 enterId 与预期 checkpoint 不一致。

原则：

> 多局无人值守的目标是“在已知流程内自动恢复”，不是“未知状态下更激进地点击”。

---

## 19. 当前未完成项清单

截至本文基线，最终全自动还缺：

- [x] **Capture lifetime 与 Run lifetime 解耦**（`stop(releaseCapture = false)`；reroll 交接路径不再停截图；单测覆盖）。2026-09-17 实机第一次通关后又发现通关路径 `markRunCleared → finishFromPlanner → stop()` 仍用默认 `releaseCapture = true` 关掉了截图，随后会话失效步骤因无截图被拒。已把所有由运行逻辑发起的停止（终态页、被拒点击、规划器停止）统一走 `stopFromRunLogic`，批次拥有该局时不释放截图；只有用户主动停止才释放；
- [x] BatchController 顶层多局状态机（`labyrinth/batch/`，纯 Kotlin，§16.1 十条 JVM 用例全部通过）；
- [x] Batch 目标配置与 UI（首页两步主流程：登录并读取当前开局 → 自动执行；五个公会各一个次数输入框，留空跳过；每行可切换「按通关计 / 按开局计」；按列表顺序执行。单独刷开局归入辅助工具）；
- [x] 显式读取且尚未推进的目标开局可作为批量首轮：启动前重新读取 `top`、`resume` 和完整地图，只在 Enter ID、公会、难度、保存路线全部一致且 `currentBlockId == null` 时跳过首轮 reroll；资格只在外部 `start()` 边界消费一次，首轮失败或结束后的批次内部循环永远回到强制撤退并重刷；已推进挑战因无法恢复角色和遗物而拒绝接管；
- [x] Batch checkpoint 持久化（SharedPreferences JSON，`AndroidLabyrinthBatchCheckpointStore`；含 `lastSessionInvalidationAction`）；
- [x] 单局明确 `Cleared/FailedMaxRetry` 终态事件（`runTerminalListener`；用户停止 → `UserStopped`，其余 → `FatalUnknown`）；
- [x] 通关后旧会话失效执行器：`AnchorTriggerSessionExpiryTerminator`。**不再有任何固定坐标点击**（2026-09-17 用户指出固定点底栏「主页」在其他页面也会落下去）。触发点由 `SessionExpiryFrameTracker.sessionInvalidationTrigger` 按当前识别页面给出：黎明界主页 → 底栏「我的主页」标签模板 `dawn_home/dawn_home_my_home_tab.png`（锚框 85,945,175,125，得分 ≥0.70 才算命中；最初用的是已选中的「冒险」标签，2026-09-17 实测点它不联网、不触发返回标题）；战斗失败页 → 结束 → 撤退（无报酬） → 确认 三步链，每步按 `LabyrinthBattleEndConfirmationDetector` 识别到的对话框阶段给按钮；其余页面 → null，终止器等待直到超时，一次都不点。录像回归夹具断言该模板在 t10.5 主页 ≥0.90、在结算链其余 9 帧 <0.50。不点「进入黎明界」，符合第 5.1 节。首次实机暴露出 `GameSessionResetWorkflow` 的一个短路：TERMINATING 阶段看到旧黎明界主页就直接 READY，终止器的结果被忽略。已改为 TERMINATING / RELAUNCHING 期间不判 READY，并抽出纯函数 `gameSessionResetReadyDecision` 加测试；
- [x] 失败页会话失效触发：2026-09-17 实机（批次 20260917-031313 run001）暴露两处空缺。(1) 策略里「刷开局」关闭时，EX 达到重试上限走的是交互模式的「保留在失败页」停止，`pendingTerminalOutcome` 为空，批次收到 `FatalUnknown` 直接 FAILED，游戏停在 战斗失败。已加纯函数 `labyrinthBatchOwnedRunEndsAtRetryLimit`：批次拥有本局时到达上限一律走 `finishFromPlannerAndRequestReroll`（FAILED_MAX_RETRY，保留截图），策略开关只管交互单局。(2) 战斗失败页没有底栏，主页标签点不到。`SessionExpiryFrameTracker` 现在暴露失败页识别到的「重新挑战」按钮中心（仅当前帧为 BATTLE_FAILED 时非空），`AnchorTriggerSessionExpiryTerminator` 新增 `pageTriggerPoint`：非空时代替主页标签，每次点击前重新读取，页面一变就回到主页标签。「重新挑战」向服务端发起新战斗请求，服务端已是新 enter，请求被拒即弹会话失效提示，不会提交失败结算（对应 §5.2「只保留一个触发按钮」）。**失败页上「重新挑战」是否确实触发失效弹窗待实机验证**；
- [x] reroll 完成 → GameSessionResetWorkflow → RecognitionSession 重启的正式接线（`LabyrinthBatchPorts` 三个端口）；
- [x] 批次首轮视为初次执行：`GameSessionResetWorkflow` 在 PENDING 阶段先只看不动（最多 6 s）识别客户端当前页面。标题 / 加载 / 公告 / 主页 / 冒险页（`gameSessionResetSkipsTermination`）尚未载入黎明界状态，直接进入入口导航，不触发返回标题；黎明界主页 / 地图 / 失败页 / 未识别才走会话失效。PENDING 期间看到黎明界主页也不判 READY（2026-09-17 用户：开始批量执行应视为初次执行，可从黎明界之前的登录流程发起）；
- [x] EX 身份探测按「每次到达挑战页」计预算：2026-09-17 调试包 152039，首次挑战页 15:13:58 进入编组打输，「重新挑战」15:16:05 回到挑战页，探测计时仍是首次的，10 s 预算早已用尽，0.4 s 后即以「未建立可信身份」停机，OCR 一次都没读到「好朋友X」（名称框 520,550,420,65 与实机文字位置核对无误）。已加 `labyrinthExIdentityProbeRestarts`：从任何其他已识别页面回到挑战页且身份未定时，重置计时与详情探测次数；UNKNOWN 帧不算离开；
- [x] 战斗失败后重刷复用了当前开局：`LabyrinthRerollWorkflow` 读到服务端现有开局且它符合目标路线时，一律 `resumedExisting` 返回成功，不看 `retireExisting`（该开关只撤退**不匹配**的开局，交互模式下用户已坐在完美开局上时保留它是对的）。刚打输的完美开局同样「匹配」，于是失败重刷把同一个 enterId 原样交回（0 分 0 秒）。新增 `LabyrinthRerollConfig.abandonExisting`：为真时跳过路线读取直接撤退；失败重刷（`startAfterBattleFailureReroll`）与批次（`rerollForBatch`）强制为真，交互刷开局保持原样；
- [x] Boss 多队第三队不再因缺 T 留空：`bossMultiTeamSearch` 只在安全容量内组队，合格一号位不够时后面的槽位直接空着，Boss 剩一丝血也判失败（2026-09-17 用户反馈）。现在安全队之后用剩余角色组「输出补刀队」（`bestSupplementFormation`，关闭首位坦克门槛）填满请求的队数，`LabyrinthTeamPlan.safeTeamCount` 标记安全队数量；第一队仍必须 T 领队，第 2/3 队（`allowSupplementLead`）允许无 T 领队；
- [x] 失败页触发改为撤退链：2026-09-17 实测「重新挑战」只回到 EX 挑战页、不联网，要重新编组到最后「进入战斗」才发请求。改走 结束 → 撤退（无报酬） → 确认：确认才向服务端撤退，旧 enter 被拒即弹会话失效。新增 `LabyrinthBattleEndConfirmationDetector`（结构判定：蓝标题条 + 底部按钮排布，三键=CHOICE、宽取消+确认=RETREAT_CONFIRM），三张用户截图入夹具 `test/resources/battle/ex_*_20260917.png`。终止器触发上限提到 6 次。首次实机（批次 195521）：点「结束」后「结束确认」的蓝标题条被 `SESSION_ERROR_TITLE` 判成失效弹窗，终止器去点「返回标题」坐标，落在「撤退」上，随后卡在确认页等标题。已改为识别到结束确认对话框时不算失效弹窗。第二次实机（调试包 152012）：三步全部点对，失效弹窗确实出现，但会话重置流程的帧处理器在弹窗后面的地图上跑了完整节点扫描（一帧 6.9 s），追踪器 10 s 没收到帧，终止器 5 s 预算到期判超时。已改为重置流程的处理器关闭节点扫描（`nodeScanRequested = false`），点击触发后等弹窗的预算单独 12 s（`popupAfterTapTimeoutMillis`，含服务端往返）。弹窗 → 返回标题 → 标题页这一段**仍待实机验证**；
- [x] 拉比林斯开局赠送角色：该公会在三个自选角色之外自动加入「菈比莉斯塔」(1068)，以单独的角色加入弹窗出现。`LabyrinthOpeningRosterConfig.grantedCharacters` 记录赠送角色，三选确认写入角色池时一并写入（`labyrinthGuildGrantedRosterMatches`），不依赖该弹窗的头像识别；弹窗本身按通用角色加入页关闭；
- [x] 一个节点方块被同时判给两个 blockId，结果点错节点（2026-09-20 调试包 020234）。路线要去 连结#30402，实际进了事件，报「节点连结#30402进入页面不匹配：识别为EVENT_CHOICE」。拓扑已经把 (820,220) 这一格按行序绑给 事件#30403、把 (810,650) 绑给 事件#30401，也就是说 30402 只能是中间那格没被检测到；而分类器把上面那格读成「连结 0.664」（日志里同时记了 TYPE_CONFLICT：expected 事件 / detected 连结），路线正好想要连结，于是这一格又被判给了 30402 —— 同一个 rect 上挂了两个 blockId，规划器挑了它想要的那个，点下去进了事件。两处收口：(1) 语义修复不再采用已被拓扑以 ≥0.70 置信度绑定的 rect——凭「它的类型正好是路线想要的」来推翻拓扑是循环论证；(2) 合并阶段，遗留的按类型映射不得占用拓扑已认领的 rect。现在这种帧上 30402 干脆没有映射，保留 TYPE_CONFLICT 继续看下一帧，而不是点下去；
- [x] 事件页点了却没反应，然后永久卡住（2026-09-20 调试包 133934）。600 帧全是 EVENT_CHOICE，横跨 323 秒，`actionCount` 始终停在 271 ——一次动作都没再发。事件 OCR 是「2315 · 稳定3 · 可信」，推荐是「23151 我还没吃够呢☆ · 可执行」，界面却一直显示「事件推荐已生成，等待按钮稳定」。这条消息只有在 `eventChoiceCommitted == true` 时才可能出现（`actionSafe` 为真时 `safetyNote` 必为 null，而 `actionSafe` 为真本该直接产出点击计划），也就是说点击早就发出去了、游戏没收到，而这个标志只在页面切换时清除，页面恰恰就是那个不变的东西。现在提交带时限：超过 `EVENT_CHOICE_COMMIT_RETRY_MILLIS`（6 s）页面仍停在事件页就重新武装并重点，最多 3 次，之后以明确理由停机，而不是无限等待。重点是安全的：事件页只接受一次选择，若点击其实生效了只是画面慢，重试计划成型时页面已经变了；
- [x] 通关后停在最终 RESULT 页不动（2026-09-20 调试包 110329）。区域5 已走 36/36，整整 325 秒、`actionCount` 卡在 440，界面报「未知或低置信度页面，动作已禁用」。RESULT 横幅匹配到 0.995，但页面分是 `min(横幅, 关闭按钮)`，而「加入的角色」这一版的按钮是**下一步**、模板是**关闭**（同一个药丸底框、字不同），按钮只有 0.60，把整页拖到 0.602；RESULT 是盖在地图上的模态，底下地图的头部和侧边按钮仍可见，NODE_MAP_VIEW 得 0.529。两者相差 0.073，差 0.08 的歧义边界 **0.007**，于是判为 UNKNOWN、禁用动作、永久停住。与 2026-09-17「获得道具」那次是同一类问题，修法也沿用同一条原则：RESULT 横幅是全屏独一份的大图，达到 0.92 即可单独认定该帧属于结算页，绕过歧义边界。没有伪造「下一步」模板——按钮本来就随子页面变化，`min(横幅, 按钮)` 要求一个不一定存在的按钮，本身就是错的；
- [x] 激活光晕是呼吸动画，判定阈值卡在它的振幅里（2026-09-20 调试包 142732）。路线目标 EX战斗#40301 就在画面中央、亮着极难的紫光，14 帧节点识别**每一帧都找到了它**，但只有 3 帧判为可点击。紫光四项指标随动画同起同落，而四个阈值（0.06/0.06/0.05/0.30）每一个都落在这个节点的亮度区间之内：

  | 指标 | 未点亮的节点 | 这个亮着的节点 | 旧阈值 |
  |---|---|---|---|
  | score | 0.00–0.02 | 0.04–0.18 | 0.06 |
  | sideScore | 0.00 | 0.04–0.14 | 0.06 |
  | lowerScore | 0.00–0.03 | 0.04–0.21 | 0.05 |
  | rowCoverage | 0.00 | 0.28–0.53 | 0.30 |

  点击还要求**连续** `NODE_CLICK_STABLE_FRAMES`(3) 帧可点击，而节点页单帧耗时 2.2 s、光晕周期比这短，于是 90 秒里出现过 5 次带 rect 的决策，却没有一次连上 3 帧——一次都没点。亮与不亮之间本来有很宽的间隔，阈值只是站错了边；现在挪到两组之间（0.035/0.035/0.030/0.22），四项仍需同时成立。判定抽成 `labyrinthNodePurpleActivation()`，回归测试直接用日志里那 14 组真实数值；
- [x] 节点点击被拒后原地重复同一像素（2026-09-20 调试包 163307）。连结#20602 匹配到 (848,238,280,350)，三次点击都落在 **988,350** 这同一个像素上，每次间隔约 7 秒；游戏把该点归给了上方节点，弹出「无法移动到此节点」，三次全废。随后轻移地图，同一节点重新匹配到 top=350，**同样 0.32 的比例**落在 y=462 被立刻接受——整个过程 41 秒。关键在于两次点击的**相对位置完全一样**（rel 112），失败的那次只是整个裁剪框registration 偏高约 112 px。所以基准比例没有问题（起初我按「模板最宽不透明行＝底座」重新标定，这个前提是错的：最宽行是怪物立绘，不是底座；改完 FinalBossPlatformLocator 的两条测试立刻红了，随后回退）。真正的修法是重试不再重复同一像素：第一次重试下移 0.30 个裁剪高（350 px 上即 105 px，正好落在 y=455，紧邻事后被接受的 462），第二次反向上移 0.15，以覆盖 registration 偏低的情况，并且始终留在节点本体内、不碰 `.bottom` 模板下方的 HUD 带；
- [~] EX 战斗失败重试会重新读取 EX 身份与「有效效果」列表——**查证为并非如此**：`BATTLE_RETRY` 提交只清战斗队伍相关状态，`currentExEncounterStrategy` 与 `effectiveExCharacterIds` 只在 `prepareNodeTransitionContext`（进入新节点）时清空；EX 身份探测也有 `if (currentExEncounterStrategy != null) return false` 的提前返回。重试时确实会重跑的是`battleRosterSearch`（`resetBattleTeamExecutionTracking`），因为重试要避开已失败的组合、必须重新组队。现有 20 个调试包里没有一个包含 EX 失败+重试，无法测量该重扫的真实耗时，暂不改动；
- [x] 扫描阶梯是单向棘轮，进了 full 就出不来（2026-09-20，按 36 个调试包统计）。全样本 320 分钟墙钟里，识别本身占 74%；`NODE_SELECTION` 占 56%，其中 95% 是我们在算而不是等游戏。按模式拆：full 6235 ms/帧、2447 窗口，占节点识别时间 **78%**；typed 5114 ms/2096；directed 1399 ms/559；tracked 291 ms/4。剔掉 3 个卡死包后，识别占 61%、full 占 12%——也就是说 full 的总账几乎全部来自卡死：`full->full` 转移 1113 次，最长一段连续 600 帧（2026-09-18 调试包 133550：68 分钟每一帧都是 full）。原因是 `updateHintMissState` 里 `FULL -> Unit`，即 full 不累加任何计数器，只有真正拿到目标才能离开。而 directed 并不是「更弱的 full」，它是同一个匹配器被约束到路线预测的位置上——在相机拟合可用时，这个约束比全图扫描是更强的证据。现在 full 连续 2 帧后，若 `expectedCenterX` 可用就清空计数回到 directed，miss 了再重新爬。实测阶梯从「full 永久」变成 `directed,directed,typed,typed,full,full` 循环，同样 30 帧 187 s → 128 s（**省 32%**）；没有相机拟合时仍然只能停在 full（有测试钉住这条）。覆盖率不受影响：真正把画外节点搬进视口的是滑动，不是 full 扫描；
- [ ] typed 档几乎白给：5114 ms 对 full 的 6235 ms（82%），窗口只少 14%（2096 对 2447）。阶梯实际少了一级，待重新设计；
- [x] 编组页耗时归因（2026-09-20）。`BATTLE_TEAM_SELECTION` 占墙钟 18%，识别占其中 88~94%。按消息分段（仅 09-19/20 的包）：自动编组 26%、有效效果扫描 21%、自动编组阻塞（角色列表变化中）14%、Boss 编组预处理 8%。单次有效效果扫描中位 21 s，且 **95% 是计算而非等待间隔**——所以调 `BATTLE_TEAM_ACTION_INTERVAL_MILLIS` 没用。角色识别已有 viewport 签名缓存，画面不变的帧本来就便宜，贵的是每个滚动步产生的新视口。另外确认：`effectiveCharacterScanStage` 只在 `prepareNodeTransitionContext` 里回到 IDLE，即**每个 EX/Boss 节点扫一次**，同一节点重试不会重扫（与 EX 重试那条结论一致）；
- [x] 旧包里那个 313 秒的编组死循环在当前构建已不存在（2026-09-14 调试包 174617：「已扫到底仍未识别推荐角色：矛依未」连续 600 帧、313 秒不退出，而详情同帧写着「角色 16/16 · 无需滚动」——不需要滚动却报扫到底，重扫永远不会有新结果）。当前代码已改为在各筛选都扫到底后「从本局可用角色中排除，重新规划编组」，有界退出。教训是这一类「不可满足的重试没有预算」和事件提交闩、扫描阶梯棘轮是同一个形状；
- [x] `stageMillis` 拆出 `battleTeam`（2026-09-20）。此前编组识别和页面分类器同在 `pageAndOther` 里，调试包无法区分该看哪一个。实测（桌面 JVM，设备约慢一个数量级）：页面分类器在**任何**页面都是 22~28 ms 的固定开销，编组页在此之上再加 21~34 ms。也就是说编组帧大约各占一半；
- [ ] 页面分类器是每帧固定税（~103 个锚点模板匹配），不分页面一律全跑。这是目前最大的**均匀**成本，但要先用便宜的判别式初筛再跑候选页锚点，属于重构，需要单独设计与充分回归；
- [x] 最终 Boss 底座定位在每一帧节点上空跑（2026-09-20 调试包 192445，设备实测）。`finalBossPlatformLocator.locate()` 原本在 NODE_SELECTION 分支里无条件执行，但它的产物 `finalBossPlatforms` 只有 `directFinalBossTarget() != null` 那条直接点 Boss 的分支会读——而这正是同一个 `finalBossOnly()` 谓词。该局在区域 3，全程与 Boss 无关，却花掉 **25.1 s / 458 s（每节点帧 189 ms，占全部识别 8.3%）**。定位器不跨帧保存状态，跳过它只少付钱、不少信息。现已与「只剩 Boss 可去就不跑普通节点扫描」合并为同一个分支：两者问的本来就是同一个问题；
- [x] 新的 `stageMillis` 拆分在真机上给出了第一份分页账（192445，7.6 分钟）：`nodes` 42.2%、`pageAndOther` 29.5%、`battleTeam` 18.7%、`bossPlatform` 8.3%。页面分类器在非编组页稳定在 **124~126 ms/帧**（BATTLE_IN_PROGRESS / UNKNOWN 中位），确认是与页面无关的固定税；CHARACTER_JOINED 的 551 ms 里含 `recognizeJoinedCharacters`，属于该页的真实工作；
- [~] 节点识别慢的真正原因（部分成立，实机结论见下一条）：directed 档用的是带 80 像素盲带的粗偏移表（2026-09-20 调试包 192445）。先记录当时的观察：typed 14 帧 × 5323 ms = 74.5 s，占节点时间 59%；directed 53 帧 × 1004 ms。**我当时的判断是错的**——我写下「即使没有精确 centerX，路线仍给出 reachableColumns，可按列范围裁掉锚点」，前提是 typed 出现在「没有相机拟合」的时候。翻开 14 帧 typed 的上下文才发现，其中 11 帧**有**完全可用的 `nodeTargetExpectedX`（935~963，正对画面中央），而且几乎每一帧前面都是 `directed,directed`：阶梯不是因为没有预测才升档，而是 directed 连续 miss 两帧后升档，**升档时把好端端的预测丢掉了**。
- [x] 上一条的改动实机**变差了**，用 212345 复盘后改方向（2026-09-20）。同口径对比 192445（旧包）与 212345（新包）：单跳搜索均值 9.7 s → 13.2 s，>20 s 的失控跳 1 次/20.7 s → 3 次/147.9 s。按档位算「每次成功获取目标的代价」才看清问题：
  | 档位 | 帧数 | 耗时 | 获取次数 | 每次获取代价 |
  |---|---|---|---|---|
  | directed | 50 | 78.4 s | 1（2%） | **80 s** |
  | wide | 12 | 24.3 s | 1（8%） | **25 s** |
  | typed | 20 | 96.6 s | 8（40%） | **12 s** |
  | full | 6 | 40.7 s | 0 | ∞ |
  旧包里 directed 同样是 53 帧只成功 1 次（2%）。也就是说**无缝网格并没有提高 directed 的实机获取率**——桌面帧上它确实从 0 个检测变成 2 个，但实机上真正卡住获取的不是覆盖率。而我新加的 `wide` 档 8% 命中，却在每一跳的 directed 和 typed 之间插进约 5 s 的过路费，是净亏。三条处置：
  (1) **撤掉 `wide`**；(2) `DIRECTED_MISS_FRAMES_BEFORE_EXPAND` 2 → 1（第二帧瞄准只是延迟真正有用的档位）；(3) 撤掉「full 两帧后回落 directed 重爬」——它把旧的「卡死在 full」换成了「循环」：普通战斗#10402 那一跳 `directed>wide>typed>full` 连转三圈 99.8 s（占全程节点预算 39%），26 帧里没有任何滑动、没有任何 rejectReason，最后是帧自己变了才被普通扫描拿到。
  取而代之的是**稳定帧记忆**：扫描是 (frame, templates, hint, 档位) 的纯函数，帧没变就不可能扫出新结果。同参数且视口稳定时直接返回上一次真实扫描的结果，最多连续 `MAX_SCAN_MEMO_FRAMES`=12 帧后强制重扫（`isStableViewport` 是抽样比较，不是全帧比较，所以要留这个兜底）。实测卡死场景 14 帧从「14 次真实扫描」降到 **3 次**（窗口数 `[695, 1464, 0, 2629, 0, 0, …]`），每个档位只付一次钱。这同时也是 2026-09-18 那个「68 分钟每帧 full」的正确解法：不是换便宜档位，是不扫；
- [x] 编组栏漏识别一个角色：不可能出现的角色在抢 margin（2026-09-20 调试包 204642）。`成员2` 连续 162 帧 `characterId == null`，整盘编组被 `下方当前成员头像未确认（成员2）` 卡住不执行点击，而它显示的 祈梨（怪盗） 就在本局自己的 32 人已加入名单里。图标包有 801 个模板，每个槽位都要对全部 801 个算分，并且要同时过绝对门槛和 `minimumRivalMargin`。黎明界的编组页只可能出现本局已加入的角色，其余 770 个不是「不太可能」而是**不可能**，却有资格把真角色的 margin 吃掉。
  改法：把本局已加入名单传进 `LabyrinthCharacterIconMatcher.match`，用它限定**谁能赢**和**谁能否决**，绝对置信门槛一点不放松。粗筛仍然扫全包（本来就便宜），所以还能顺带判断名单自己是否可信：若全包最优比名单内最优高出 `ROSTER_OUTSIDER_OVERRIDE_MARGIN`=0.08，说明画面上的脸根本不在名单里（名单漏记、或这压根不是黎明界编组页），此时退回全包行为——把「没认出来」变成「自信地认错人」会更糟，因为规划器会照着错的答案去取消并重选队员。两条测试分别钉住这两面；
- [x] 截图 OOM：每帧新申请一块 8 MB 像素缓冲（2026-09-20 实机 `Failed to allocate a 8294416 byte allocation with 7140992 free bytes ... growth limit 201326592`）。8294416 正好是 1920×1080×4，而 192 MB 是未开 largeHeap 的默认上限。两处一起改：`android:largeHeap="true"` 抬高上限；`rgba8888ToArgbPixels` 的 `IntArray`/行缓冲改为池化复用（每个像素都是先写后读，且 `Bitmap.createBitmap` 会在下一帧转换前把它拷走，所以复用不会把上一帧的像素带进来，有测试钉住）。前者是给余量，后者才是不再每帧churn 8 MB 的实际修复；
  于是问题变成「directed 为什么一直 miss」。在 node-target-missed 这张实测帧上直接量：directed（粗网格，3+1 提名）**一个节点都找不到**，610 窗口 ~170 ms；同一张帧换成无缝网格，640 窗口 ~180 ms，**找到 2 个 EVENT 节点**。也就是说 directed 不是算力不够，是它瞎——它用的偏移表在 160 和 320 之间跳 80 像素，而模板离节点 8 像素就跌破 0.60 接受线（`NodeScanCoverageTest` 早就钉住了这条物理）。2026-09-16 加无缝网格时留了一句「directed 已经对准目标，可以继续用粗表」——这个理由不成立：对准的是**列**，盲带落在列内的哪个位置它管不着。
  改动：(1) 偏移表不再分粗细，所有档位统一用无缝表；(2) directed 的提名预算 3+1 → 4+2；(3) 阶梯在 directed 和 typed 之间插入 `wide` 档（容差 0.55 → 1.15 个列距，其余同 typed），把「放宽瞄准」和「放弃瞄准」拆成两步，而不是一次让渡四件事。
  提名预算为什么必须一起动：无缝网格 + 3+1 虽然找得到，但定位偏——下方 EVENT 报在 y=612，而所有贵档位一致认为是 650；node-link 上报 (664,188) 而贵档位一致是 (689,176)。裁剪框偏多少，点击就偏多少，这正是调试包 163307 里 连结#20602 连点三次被拒的那个失败。4+2 在两张帧上都复现了贵档位的精确位置（~225 ms 对 ~180 ms）；6+2 和加颜色候选也量了，检测结果和位置都没再变，所以预算停在 4+2。
  账：单跳从 `directed(1004) + directed(1004) + typed(5323) = 7331 ms` 变成一帧 directed（预计 ~1400 ms）。directed 单帧贵约 35%，但省掉的是每一跳两帧空转加一次 5.3 秒重扫。`DIRECTED_WINDOW_BUDGET` 相应从 800 提到 1100，并新增「必须低于全扫描一半」的相对断言（实测 1056 对 2629）；
- [x] 第三层商店偶发只买 1~2 件就提前退出（2026-09-21 调试包 134750）。三件商品画面清晰、裁剪正确、购买键可用，但 `LabyrinthShopCategoryResolution.attempt()` 按**经过时间**推进档位（`now - started >= 3000` 每 3 秒进一轮），与实际 OCR 完成无关。OCR 是全局串行队列、单次 2~5 s，9 秒内只跑得完约 3 次，覆盖不了 3 件 × 3 套裁剪；仍在识别的商品被三轮时钟判为 `EXHAUSTED` 跳过，随后「没有可确认购买项」退出。日志 rows 576-590：三件全程 `READING`、`titleText=null`，档位却 1→2→3 每 3 秒一跳。改为**按完成推进**：只有当前裁剪的 OCR 真正返回（`read != null`，成功或空都算）才退掉该档进下一套（`noteVariantOutcome`），未完成就继续等。三套裁剪仍是上限，只是不再被时钟提前烧掉。
  - **自审修正**：首版兜底写成「该档位已当前 12 s 就强退」，这是把同一个 bug 换了个数值——兜底计的是**排队等待**，而队列是串行的。用 134750 实测：单次 OCR 中位 2.5 s / p90 3.7 s / 最大 6.4 s，完成间隔 p90 5.4 s，第三个槽位轮到自己 p90 要等约 16 s，12 s 兜底会在它第一次识别完成前就烧掉一档。改为按**引擎存活**计时：`noteVariantOutcome` 收 `engineLastCompletedAt`（`RelicOcrScheduler.lastCompletionAt()`，任意槽位有回调就刷新），只有整个引擎连续 20 s 一个字都没吐出来才强退一档。排队等待不再计入，引擎真死时最迟 60 s 退出商店，不会永久挂起；
- [x] 移除 UNKNOWN 页面的兜底中心点击（960,780），即战斗/事件结束回大地图时那个「定位点击」（用户确认完全移除）。它在过渡帧被判为 UNKNOWN 时点中心，地图已加载时正好点到节点，弹出非自动触发的移动确认弹窗，随后被「取消误触」关闭，而取消会重置节点跟踪、退回全图扫描——一次白折腾数秒。该点同时也是角色获得动画（`ADVANCE_CHARACTER_ACQUISITION`）与后加的立绘恢复（`COLLAPSE_PORTRAIT`「尝试收起角色立绘」）的推进点，两者一并删除。**权衡**：极少数只暴露 UNKNOWN 立绘帧、需要点击才翻页的奖励链（部分 link/event 奖励）不再自动推进，会走已有的 30 秒未知页超时后停机保留诊断；绝大多数奖励经由可识别页面（EVENT_ANIMATION、CHARACTER_JOINED 关闭、开局邀请规划器）推进，回图后 `handleNodeSelectionFrame` 立即清空获得动画上下文，常见路径不受影响。`LabyrinthPortraitRecovery` 与 `portrait-unknown-recovery` 文档随之移除，保留 `labyrinthHasBattleControls`（仍用于否决战斗页盲点）。
  - **自审补充清理**：删除后 `labyrinthEventUnknownFallbackTap/Point` 与 `LabyrinthFallbackTap.EVENT_LEFT/EVENT_RIGHT` 在生产代码里已无调用方，只剩测试吊着；这两个枚举值的含义是「允许在 UNKNOWN 页面盲点屏幕左右边缘」，没有主人的盲点许可留在许可表里会误导后续改动，一并删除（含 `LABYRINTH_FALLBACK_TAP_POLICIES` 里对应两行与相关断言）。
  - **仍然存在、但判定在范围外的两处同坐标点击**（已告知用户）：① 开局邀请规划器 `LabyrinthEntryActionPlanner.CHARACTER_ACQUISITION_CONTINUE = (960,780)`，只在 `entryPhaseComplete == false` 的开局阶段运行，此时尚无地图可误点；② `ADVANCE_FINAL_SETTLEMENT` 在 UNKNOWN 页的结算推进（`finalSettlementFallbackRect`），只在 `postBossStage` 为 `BEFORE_SCORE`/`CHEST_SEQUENCE` 时生效，即最终 Boss 之后，背后同样没有地图；
- [x] 全面清查「无限等待」：路线阶段所有页面加通用停滞看门狗（2026-09-22，按用户要求「目标中不能有无限等待」）。查证结论：页面级的重试上限**只统计点击次数**（`postEntryAttempts` 仅在派发动作时累加），而决定「等待」的分支一次点击都不发，于是什么计数都不涨、什么超时都不适用——整个路线阶段**只有 UNKNOWN 页有 30 秒死线**（`UNKNOWN_POST_ENTRY_TIMEOUT_MILLIS`）。`plan == null` 那条路只发一条状态消息就 `return`，因此以下每一个都能无限挂着：商店商品识别不稳、EX 挑战页遭遇攻略始终识别不出、事件 OCR 永远达不到可信阈值、公会招募类事件因「已有角色资料缺失」永远 `actionSafe=false`、战斗失败页安全重试条件永不成立、连结/遗物选择页等待条件不满足。
  - 新增 `labyrinthPageStallExpired`：同一页面、同一条状态消息、且一次动作都没派发，连续 120 秒即带诊断停机。「有进展」的判定刻意放宽——派发动作、页面切换、**或仅仅消息文本发生变化**都算，因为消息变了就说明某个子状态在推进（商店轮次、OCR 确认次数、扫描阶段）。120 秒的取值留足余量：商店单个标题的 OCR 兜底是 20 秒，串行队列下第三个槽位还要排约 16 秒，最慢的合法沉默约半分钟。
  - 两处合法长等待显式豁免：`BATTLE_IN_PROGRESS`（已有 `LabyrinthBattleWaitPolicy` 的 5 分钟固定死线）与人工选角（操作者自己结束）。
  - 另外两条**绕过该处理器**的死等一并封口：① 系列详情弹窗（手动打开、程序故意不关，原本完全无死线）加 5 分钟上限；② 孤立移动确认弹窗取消次数用尽后显示「请手动关闭后继续」并无限等待——无人值守时这恰恰是最不该做的，因为此时任何地图手势都会落到弹窗上，其它一切也都无法推进，现加 60 秒宽限后停机。
  - 同时核对了事件资源与规划器的覆盖：22 个事件的全部选项都有效用规则（16 条走 `ROLE_CLASS_BY_CHOICE` 职阶池与 `BATTLE_REWARDS` 斗技场表，不会落到「选项ID无效」）。节点页本身已有终止出口（扫描轮次、微调轮次、点击次数、移动确认超时），无需新增；
- [x] 事件里金币不足的选项被反复空点直到停机（2026-09-22 用户截图，无日志）。「发现了不可思议的石板！」三个选项中，slot2/slot3 在工坊里就带着 `conditionType=1, conditionValue=2000`，而当时只有 1,550 金币。规划器本来就有「优先选当前是蓝色的按钮」这条回退，但**它在这里救不了场**：游戏把买不起的按钮画成和免费按钮同样的蓝（`labyrinthEventBlueButtonConfidence` 三个都远高于 0.18 阈值），像素看不出拒绝。于是点了、页面不动、6 秒后重试、`MAX_EVENT_CHOICE_COMMIT_ATTEMPTS` 用完停机，期间界面一直显示「事件推荐已生成，等待按钮稳定」——这句话描述的是等按钮，真相是点击已经发出去并被吞掉了。**用户指出灰蓝与蓝是可分的**，据此改为像素直接判定，不再依赖「点了才知道」。用户截图按生产 8×16 采样网格实测：可用按钮中位 RGB (99,166,247)，两个买不起的是 (82,101,173) 和 (74,101,173)——蓝通道 173 仍然高于原阈值 105，所以旧判据分不开。新判据 `亮蓝 = B≥200 且 B−R≥60`：可用 0.54，两个灰蓝都是 **0.00**；单选钓鱼夹具 0.55，阈值取中间的 0.35。
  - 一分为二：`buttonConfidence`（宽松，「这里画着一个按钮」）继续供 `labyrinthCompleteEventLayoutCount` 判断选项数——灰按钮也是按钮，这里收紧会误判布局；新增 `enabledConfidence`（严格，「这个按钮能按」）供规划器决定可执行性。安全提示也改为「推荐按钮为灰蓝不可用状态(亮蓝0.00)，通常是金币或条件不足」。
  - 同时保留**按拒绝退选**作为第二道防线：`decide()` 的 `rejectedChoiceIds` 记录提交过但页面没变化的选项并从排序里排除，覆盖像素判据之外的门槛类型（例如条件不足但仍画成亮蓝的情况）。停机阈值随选项数放宽（`max(3, 选项数+1)`）。未引入金币余额识别：像素已能区分，余额 OCR 需要新锚点和夹具，收益不足；
- [x] 事件自由选角页可能永久卡住（2026-09-22 用户截图，无日志）。该页与开局选角共用同一个外壳（截图里被判为 `INITIAL_CHARACTER_SELECTION 0.995`），归属已由 `labyrinthEventFreeRoleSelectionOwnsFrame` 按路线上下文区分，这部分没问题；问题在于 `handleEventFreeRoleSelection` 的三条「等待」分支**全都是无限等待**，而这个页面没有任何其它超时兜底：① 视口 `recognitionState != STABLE`（满屏角色里只要一个头像认不出就可能一直不稳）；② `decideFreeVisibleRole` 返回 Wait（本局已有 32 名角色，而名单有超长滚动条，首屏完全可能全是已获得角色）；③ 推荐不满足 `actionSafe`。三条都只发一条消息就 return，永远不计次数、不推进。**用户明确了该页语义**：名单里不会出现本局已获得的角色，所以「首屏全是已有角色」不成立，也就不需要翻页找人——直接取当前页可识别的最高分角色即可，多选时按当前页评分从高到低依次选。据此：
  - `decideFreeVisibleRole` 改为**按角色自身评分（`effectiveUserScore`）对当前页排序取最高**，不再调用 `chooseOneRole`。后者会对每个候选跑一遍队伍组合搜索，三张卡的奖励页尚可，整屏名单则代价随卡数增长——这正是用户说的「省时间」。多选场景无需额外逻辑：已选中的卡会带着 `selected` 标记回来并计入已获得集合，下一次自然落到次高分。推荐理由里同时给出本页最高分、次选和可识别人数，便于复核。
  - 我上一轮加的「翻 12 页名单」按此删除；`labyrinthEventFreeRoleBlockedAction` 简化为等待/停机两态。**保留 45 秒停机兜底**：这一页原本三条等待分支全是无限等待且没有任何其它超时，一个认不出的头像就能静默挂住整局，与是否翻页无关；
- [x] 「遗物效果结果」弹窗无法识别、无法关闭（2026-09-23 用户截图）。遗物把战斗结果从败北翻成胜利时会弹出这个框，它与「获得道具」「迷宫遗物效果」同属一套 关闭 按钮家族，但**是一个居中小对话框**而非那两者的整屏外壳：标题在参考系 y=252（另两者是 50），按钮在 y=700（另两者是 895）。因此已有的两组标题锚点和那个整屏关闭锚点**没有一个能看见它**，页面落到地图可见的未知态，也没有任何规则能关掉它。
  新增 `RELIC_EFFECT_RESULT_TITLE`/`RELIC_EFFECT_RESULT_CLOSE` 两个锚点（模板取自用户截图，按参考系 1920×1080 还原；两个矩形都以对话框中心 x=960 对称），分类器把「标题+自带关闭键」也算作 ITEM_REWARD，关闭计划优先取这个对话框自己的按钮——通用 `MODAL_CLOSE` 兜底点在 y=870，比它低约 190 参考像素，会完全点空。截图已入夹具 `relic-effect-result-20260922.png`，回归测试断言页面判为 ITEM_REWARD、两个锚点得分 ≥0.80、且点击落点在该对话框按钮范围内。两张模板一并登记进 `vision.json`（含尺寸与 sha256），否则 `VisionResourceContractsTest` 会因清单缺项而失败；
- [x] 1.0.13（2026-09-24）。合并主流程重构与三项外部贡献。首页收敛为「读取当前开局 → 自动执行」两步，刷开局设置移到右上角，单独刷开局与实时日志归入辅助工具；新增独立读取状态，把「尚未读取 / 正在读取 / 等待验证码 / 没有开局 / 符合 / 不符合 / 失败 / 已取消」从可空的 `routeVerdict` 里拆出来，「当前没有开局」不再退回含混的「账号已登录，尚未读取」。公会不再属于全局设置：读取当前开局只比路线与难度，批量公会由首个批量目标决定，单独刷开局的公会按次选择且不写回设置。节点进入页面与路线预期不符时不再直接停机，改为按实际识别到的页面继续并记录差异；角色三选一的候选与本地已获得名单冲突不再视为致命，记录诊断后仍按实际画面打分。页面模板匹配把「模板减均值」与坐标映射表提到内层循环之外，分数逐位不变。事件自由选角死锁与「遗物效果结果」弹窗误判见 1.0.12。合并时保留了两侧各自独立的新增（复用资格校验、批量/单独刷开局配置助手、两份文档），冲突以本分支的公会模型为准，并在新结构里补回了「显式读取且未推进才铸造一次性复用资格」的调用。全量单测 1028 项 0 失败。
- [x] 1.0.12（2026-09-23）。修事件自由选角死锁与「遗物效果结果」弹窗误判。前者三因叠加：选中状态在派发点击时记账且从不复核，游戏忽略的点击留下幽灵选中把每一帧都推进确认分支；「去邀请」启用/禁用两张模板共用同一 ROI，灰按钮上启用模板仍得 0.925，单阈值把灰读成蓝；选人分支每次派发前重置进度，45 秒放弃计时器永不触发。现改为每帧以角色列表对账选中状态、按两张模板的分差判定按钮可用性、只有画面上选中人数真的变多才重置计时。后者此前既无模板也不属于任何确认弹窗页，会被判为会话失效并准备点「返回标题」，属会毁整局的误动作。详见下方两条；
- [x] 1.0.11（2026-09-22，续修）。修买印记后整局静默冻结。2026-09-22 调试包 173753：599 帧 / 408 秒停在 `INITIAL_CHARACTER_SELECTION`，`actionCount` 全程冻结在 379，消息始终是每帧通用的「页面识别已更新」——没有任何处理器发过话。三个独立缺陷叠加：
  - **动作锁泄漏会静默冻结一切**（根因）。`actionInFlight` 是单动作闩锁，而**每个帧处理器开头都会在它被持有时直接 return**。12 个派发点全都是 `compareAndSet(false,true)` → `actionScope.launch { … ; set(false) }`，释放是协程体的最后一句、**不在 finally 里**；派发体最长 228 行（`dispatchPostEntryTap` 的成功分支要做名单/商店入库等大量状态处理）。体内任何异常、或 `executor.execute()` 永不返回，闩锁就永久持有，此后没有点击、没有消息、也没有任何其它超时会被求值——因为根本到不了求值的地方。现改为 `beginGuardedAction()` + `launchGuardedAction { … }`，释放放进 `finally`；另加 `labyrinthActionLatchStuck`：闩锁持有超过 60 秒即释放并带诊断停机（真实手势远低于 1 秒）。
  - **中途的角色选择器把整局打回入口阶段**。开局选角、商店选择印记、事件自由选角是**同一个页面、同一个标题锚点**，`labyrinthShouldResumeOpeningSelection` 只看标题分 ≥0.85，于是买印记弹出的选择器被认成开局选角：`entryPhaseComplete` 被清零、入口规划器被重建，而入口规划器对这个选择器没有任何规则，同时 `nodeReplayActive` 也因此为假——没人拥有这一帧。现加 `routeActive` 判据（`nodeSession != null`）：路线已在执行时，这一页只可能是本局自己打开的选择器，不再交还入口阶段；对应地 `labyrinthEventFreeRoleSelectionOwnsFrame` 也接受 `routeActive`，不再依赖「记得是哪个节点打开的」——而那份记忆恰恰会被购买引起的页面抖动清掉。
  - **看门狗的人工选角豁免是个洞**。上一版新加的停滞看门狗对「等待人工选择角色并确认」免检，但 `manualCharacterSelection` 在本构建里硬编码为 `false`，而 `INITIAL_CHARACTER_SELECTION` 的等待消息仍会回落到这一句——于是任何角色页的卡死都自己豁免掉了，正是这次卡死的页面。该豁免已删除，只保留战斗页（它有独立的 5 分钟死线）。
  - 另按用户要求：商店印记**优先买更便宜的随机印记**而不是选择印记（截图：随机 780 / 选择 1,820）。同职阶两者奖励等价，选择印记却贵一倍多、还要额外应付一个全名单选择器；同类型内仍按最左槽位取，保持确定性；
- [x] 1.0.11（2026-09-22）。事件修复与死等清查。事件里买不起的选项改为按「亮蓝」像素直接判定不可用（`B≥200 且 B−R≥60`，可用 0.54 / 灰蓝 0.00），不再点了才知道；保留按拒绝退选作为第二道防线。事件自由选角改为只看当前页、按角色评分取最高，多选依次下推，不再跑队伍组合搜索也不翻名单。新增路线阶段通用停滞看门狗：同页面、同消息、无动作连续 120 秒即带诊断停机（战斗与人工选角豁免），并补上系列详情弹窗与孤立移动确认弹窗两条绕过路径的死线。详见下方三条；
- [x] 1.0.10（2026-09-21）。性能版。节点扫描不再花在会话丢弃的帧上（稳定窗与待确认移动期间跳过，`deferred`），弹窗关闭冷却改为从手势实际落地起算（消除角色加入弹窗双击及其误触移动确认）；页面锚点与节点扫描共用一次全屏亮度/梯度预计算（锚点评分 23.8 → 6.9 ms），捕获间隔 100 → 300 ms 以压低 GC；编队搜索 4 线程并行 + 评分热路径去分配；普通战保留当前满编队伍直接开战；EX→Boss 复用「有效效果」扫描；视口像素回忆在 signature 抖动时保住相机预测。详见上方「参考/优化版 fork 审批移植」与「节点扫描花在会话根本不看的帧上」两条；
- [x] 1.0.9（2026-09-20）。修复通关后停在最终 RESULT 页不动；修复激活光晕呼吸动画导致节点点不下去；
- [x] 1.0.8（2026-09-20）。本版包含：同一局第二次遇到同一个 EX 的识别死锁、一个节点方块被同时判给两个 blockId 导致点错节点、事件页提交后永久卡死、以及诊断包截图与分数的配对说明。版本号只在 `build.gradle.kts` 一处声明，`AppVersionTest` 盯住它，避免只改 gradle 而忘了同步发布动作；
- [x] 正式版本号 1.0.7（2026-09-20）。`versionName`/`versionCode` 由占位的 `0.1.0-dev`/`1` 改为 `1.0.7`/`10007`（major×10000+minor×100+patch，保证名字变化时编号仍单调）。版本来自唯一来源 `AppVersion`，出现在：进程启动日志首行（`LandosolToolbox: 黎明界助手启动：版本 1.0.7 (10007)`）、诊断包 `README.txt`、`logs/logcat.txt` 首行、`state/latest.json` 与 `state/history.ndjson` 每条记录的 `appVersion`/`appVersionCode` 字段、网页面板标题与顶栏、「黎明界」页面标题下方。动机是 2026-09-19 的 185837 调试包：它描述的行为在本仓库任何提交里都不存在，却无法判断出自哪个构建；
- [x] 同一局里第二次遇到同一个 EX 就卡死（2026-09-20 调试包 002652）。美空在这一局出现两次：第一次正常识别并打完，第二次连续 18 帧都显示「正在识别特殊EX右侧本体：美空」——名字每帧都读对，却始终不可信。魔物详情的名字行两次渲染像素完全一致，`RelicOcrScheduler` 指纹命中缓存，第二次一次 OCR 都不会再发；而「两次独立读取」是在 resolver 里按 `evidenceId` 数的，离开挑战页时清零，回来后只看到缓存里那个恒定的 id，加到 1 就不动了，等一次调度器按设计永远不会安排的读取。与 173956 的槽位抢占不是同一条，`ABANDONED_SLOT_MILLIS` 那个修复在这里不适用。现在计数下沉到调度器：`Read.confirmations` 在文本与上次一致时累加，缓存命中天然带着已确认次数返回；两次读取不一致时额外补一次读取（只补一次），避免等一致的调用方永久等待。resolver 删掉两套 `evidenceId` 计数器，改为 `confirmations >= 2`；
- [x] 节点扫描花在会话根本不看的帧上（2026-09-20 调试包 225220）。全程 402 s，节点阶段合计 129.8 s，其中**只有 54.4 s 属于真正在找目标的 13 帧**；另外 75 s（58%）扫的是 `handleNodeSelectionFrame` 开头两道门直接丢掉的帧：「弹窗关闭后等待节点地图稳定」25 帧 47.6 s，「已点击/已确认移动，等待游戏反应」17 帧 27.4 s。这些帧每一帧都老老实实跑 directed（1.3~2.7 s）或 typed（3.3~7.8 s），结果 `nodeClassifications` 一个字节都没被读。更糟的是它们不是白扫，是**反向扣分**：每个丢弃帧的 miss 都计进阶梯，真正被读的第一帧已经站在 typed 档上；而 2 s 稳定窗只放 1 帧进来，下一帧要等它扫完（4~7 s）——一跳从关弹窗到点节点实测 8.6~10 s，纯滚图/点击只需 1 s。处置：处理器新增 `nodeScanConsumable` 谓词（会话侧 `nodeScanConsumable()` 镜像那两道门），不可消费时跳过整段地图扫描、`nodeSearchMode` 记为 `deferred`，页面级识别照跑、跟踪器状态原封不动；会话侧对 `deferred` 帧不喂 `processClassifications`（空列表会被当成「看不到任何节点」把相机坐标清掉）。预期每跳省 2~5 s，全程约 60~75 s。
  - 同一包里三选一本身不慢（OCR 每槽 100~250 ms，三选一页到点选 1.5~2 s），慢在**关角色加入弹窗被点了两次**（8 个弹窗中 3 个）。冷却按「决定上一次点击的那一帧的采集时间」算，而手势实际落地要晚 0.3~1.1 s、弹窗再淡出 0.3~0.8 s；下一帧采集时点击才刚落地甚至还没落地，弹窗仍在，650 ms 冷却却已过。第二下点穿淡出的弹窗打到地图节点，弹出移动确认再被「取消误触」——一次白折腾 4 s。现在冷却基准取 `max(决定帧时间, 手势实际执行时间)`（`labyrinthPostEntryCooldownRemaining`），点击落地后才开始计时；
- [x] 「参考/优化版」fork 审批移植（2026-09-21）。fork 从 d0df39c 分出，9 个文件有差异；逐项核对后 5 项审批结论：批准 2、有条件批准 2、驳回 1，另采纳 1 项审批单外改动。已移植：
  - **帧特征共享**（fork 第 2 项）：页面锚点评分从不调用 `prepareFrame`，每个采样点走 5 次 `luminance()` 的慢路径；节点分类器又自己再算一份全屏梯度。现在 `LabyrinthEntryFrameProcessor.process` 在 try/finally 里准备一次，节点分类器借用同一份帧特征（`sharedFrameFeatures`，`hasPreparedFrame` 命中就不再重算；采样密度仍各自独立），两块大数组跨帧复用。1080p 夹具实测（桌面 JIT）：103 个锚点窗口评分 23.8 ms → 6.9 ms，准备一次 6.4 ms；节点帧再省掉分类器那份 ~6 MB 的重复计算。分数逐锚点相等（`GradientTemplateMatcherCacheTest`）。
  - **捕获间隔 100 → 300 ms**（审批单外）：会话每 500 ms 只消费一帧，100 ms 转换等于每消费帧白做 4 次 8.3 MB 像素缓冲 + 8.3 MB 位图。09-20 四个包每局 GC ~350 次、停顿 ~10.6 s、堆常驻 176/200 MB。
  - **EX → Boss 复用有效效果扫描**（第 5 项）：同一遭遇的 EX 与 Boss 共用「有效效果」筛选结果；`prepareNodeTransitionContext` 仅在 EX→Boss 这一跳保留扫描，其余进入一律重置，角色表指纹（`labyrinthEffectiveScanRosterStamp`）变化也重置。顺手修了 `start()` 里 `resetExEncounterTracking()` 连调两次的 bug（第二次本该是重置有效扫描）。经结算页/挑战页兜底恢复的 Boss 上下文不经过这一跳，仍会全量扫描——安全方向。**实机未验证**：四个包里没有一局同时打过 EX 和 Boss。
  - **评分热路径去分配**（第 1 项，只取去分配部分）：`evaluateInternal` 的属性加成从 `groupingBy/map/filter/sortedByDescending/take/associate` 改为按 ordinal 索引的定长数组单趟累计；首次出现顺序、稳定排序平局处理与旧链一致（`LabyrinthTeamCompositionPolicyTest` 钉住）。**未移植**同一 diff 里的 4 线程并行搜索与常驻线程池（独立决策，fork 自述"越多越慢"），**驳回** `ROLE_REWARD_ROSTER_SEARCH_LIMIT = 19`（三选一搜索池 24→19 会改变选择结果；四个包里 `roleRewardDecisionMillis` 最大 921 ms，多数为 0，没有它要解决的 28 s 问题）。
  - **视口像素回忆**（第 4 项，补测试后）：`expectedScreenCenterX` 在 signature 未命中时用采样像素比对最近 3 个快照（`MAX_PIXEL_RECALL_CANDIDATES`），相机未动即复用其世界坐标拟合。fork 原版遍历全部快照且无上限；已限制候选数，并补了三条测试：静止相机 signature 抖动仍有预测、同背景平移后的另一段不得借用拟合、超出窗口的旧快照不再命中。收益预期有限：三个包里"有目标但无预测"的帧只有 6/45、10/69、2/33。
  - 第 3 项「普通战保留当前满编队伍」我原本驳回（推荐仍照算，省的只是几秒选人；而每场普通战推荐会因新角色加入而变，用旧队打新战等于弃用推荐）。**用户复核后决定采纳**：普通战难度低，省时间优先。已移植：`plan(holdCurrentTeamRequested)` 在成员条 5 格全部识别出身份时把当前队伍当作推荐，diff 为空直接开战；缺人或任一格身份未识别一律走正常 diff；会话侧仅 `combatContext.kind == NORMAL && battleRetryCount == 0` 时请求，失败重试回到推荐路径。`isStillValid` 对保留计划跳过「推荐是否变化」比较，视口/筛选/成员条变化仍会作废计划。
  - 第 1 项的 **4 线程并行搜索**同样在用户复核后采纳：`LabyrinthTeamOptimizer(searchThreads = 4)`，组合数 ≥ 512 时把词典序枚举均分给常驻守护线程池（`labyrinth-role-search`），各段取局部前 N 再按 (分数降序, 枚举序号升序) 合并。串行与并行共用同一个 `CombinationFilter`，平局用枚举序号而非「通过筛选计数」，两者沿同一枚举严格递增，赢家相同；24 人池 / 三种上下文 / 三种入口的逐位等价测试通过。线程数刻意不跟 CPU 核数（fork 实测 4 线程 0.58x、8 线程 0.45x，游戏与助手同机）。
  - **暂缓** fork 对 `LabyrinthNodeClassifier` tracked 帧的放宽（`trackedFrames > 0` 即继续、上限 5→20）：它基于分叉前的 `DIRECTED_MISS_FRAMES_BEFORE_EXPAND = 2`，HEAD 已改为 1 并加了稳定帧记忆，需重新评估。
- [x] 迷宫大师开局赠送遗物弹窗「迷宫遗物效果」：与「获得道具」同壳、同关闭按钮，仅标题与说明不同，此前判为 NODE_MAP_VIEW 不会关闭。新增 `relic_effect_title/instruction` 模板，分类器把两套标题任一命中都算 ITEM_REWARD；两张用户截图入夹具（`test/resources/labyrinth/*-20260917.png`）；
- [x] EX 第二次失败换奶兜底不再阻塞：失败队五人全是有效效果角色时，此前拒绝一切换出并停在失败页。现在有效角色「最后换、不是不换」：优先换非有效的最低分，没有就换有效角色里最低分的一名；连奶都没有时回退普通 fallback 搜索，不再返回不可用；
- [x] 事件后「无法获得报酬」单按钮确认弹窗：没有自己的页面状态，判为 UNKNOWN 后无人点击。新增 `labyrinthGenericConfirmDialogRect`：UNKNOWN 帧上「确认」按钮模板（复用日期变更弹窗的同款按钮，≥0.85）命中、且排除日期变更 / 会话失效 / 移动确认 / 结束确认四类同壳弹窗时，点它（动作 `CONFIRM_GENERIC_DIALOG`）。用户截图合成到 1080p 入夹具 `event-no-reward-confirm-20260917.png`。首次实机（2026-09-18）：真机帧上蓝标题条与「错误提示」模板得分 0.64，会话阻塞判定抢先，等「返回标题」等了 255 次动作；页面又被地图判成 NODE_SELECTION，只认 UNKNOWN 的规则够不着。已改为：会话阻塞判定先排除识别到确认弹窗的帧；确认弹窗允许出现在地图类页面（UNKNOWN / NODE_SELECTION / NODE_MAP_VIEW / EVENT_CHOICE / EVENT_ANIMATION）上并优先于页面分支；区分依据改用按钮（返回标题为蓝、确认为浅色），不再要求标题条低分。真机帧入夹具 `event-no-reward-confirm-live-20260918.png`；
- [x] 事件自由选角支持选 2 名：选角数量不可读，但「去邀请」只有选够才亮。改为：亮了就确认（不论几名）；选完一名 2.5 s 后按钮仍识别为灰色则再选一名，上限 3 名（`labyrinthEventFreeRoleNeedsAnotherPick`）。确认时把全部已选角色计入待获得列表；
- [x] 有效效果角色为 0 时扫描阻塞：切到「有效效果」筛选后列表为空、显示「未搜索到该角色」，扫描一直等卡片和滚动反馈（2026-09-18）。新增模板 `battle_team_selection/roster_empty_notice.png`（锚框 802,527,297,38），`labyrinthEffectiveFilterIsEmpty`：有效效果筛选 + 画面稳定 + 无卡片 + 提示命中 ≥0.80 时直接视为 0 名对策角色，恢复全部筛选并开始编组；
- [x] 商店退出确认弹窗被当成节点移动确认（2026-09-18 调试包 161911）：弹窗淡入那一帧页面判为 UNKNOWN，但商店标题/关闭按钮仍是 1.0。通用双按钮检测器命中后，「孤立弹窗收养」路径把它认作路线上唯一剩余后继 Boss#30701 的移动确认（该节点从未被点击过），随后等 Boss 进入页、看到商店页，判为进入页不匹配并停机。取消路径本来就有商店背景防护，收养路径没有；已给 `labyrinthCanRecoverOrphanNodeMoveConfirmation` 加 `shopBackgroundVisible`，可见商店界面时一律不收养；
- [x] 商店内每次购买都报「检测到账号会话失效」：购买确认/完成/退出确认三个弹窗共用蓝标题条，正是断线重连模板裁切的来源，弹窗之间的过渡帧判为 UNKNOWN 时会话阻塞判定就命中。已改为：商店界面可见且未识别到「返回标题」按钮时不算会话阻塞（没按钮本来也无从处理）；商店内真正的会话失效仍会显示该按钮，照常处理；
- [x] 路线要去商店却反复点 Boss 平台（2026-09-19 调试包 222537）。协议下一节点是商店#30601，画面上唯一识别到的是本区域的 Boss 平台。Boss 平台走的是独立的特殊节点锚点，裁剪框 500×350，与普通节点的 280×350 不是一回事；而一个只有 1 个节点的逻辑列配上 1 个检测，天然满足 FULL_COLUMN，拓扑置信度给到 0.990。有序拓扑覆盖规则因此把「期望商店、识别 Boss」这个冲突当成模板噪声放行，于是照着 Boss 平台的位置点下去，等一个永远不会出现的移动确认弹窗，直到人工滑动才真正点到商店。已规定只要冲突任一侧是 Boss 就不允许任何覆盖：该规则本来是为「连结/遗物」这类外观相近的普通节点图标设计的，Boss 平台是完全不同的地图资产，把它读成商店不是噪声而是相机位置判断错了，必须停下来而不是硬点；
- [x] 遗物优先级可自定义（2026-09-19）。策略设置新增「遗物优先级」分组，可按顺序排列加速/会心/守备/强化/弱体，留空沿用内置顺序。顺序影响两处：主追印记从「当前层数最高的未满印记」改为「排序最高的未满印记」，以及候选打分里按名次加一个小权重。有意不影响的部分：能一次跨到 15 层的选项仍然优先，已满 15 层的印记仍然不选——这两条是实际收益而非偏好；
- [x] 后期变慢的来源（2026-09-19 实测，非角色数量）。把全部调试包按页面统计单帧识别耗时，NODE_SELECTION 均值 3511 ms、p95 7647 ms，其余页面都在 1 秒以内；再按搜索模式拆开：`full` 占节点帧的 51%、均值 6215 ms、约 2468 个窗口，`directed` 1200 ms/548 窗口，`tracked` 206 ms。也就是说成本几乎全在兜底全扫描，而不是角色变多。另外用 8→32 人的角色池直接压测编组规划器：单队 0→109 ms，Boss 三队 1→250 ms（桌面），确实随人数增长但不是瓶颈。已做的优化：同一帧内按矩形缓存节点分类结果。相邻锚点各自扫 ±320 参考像素，比锚点间距还大，同一个矩形会被多个锚点重复提出，此前每次重复都要付一次 2400 采样的完整分类；结果只取决于（帧、矩形、模板集），本帧内全部固定，所以缓存不改变任何判定。实测窗口数 2730→2491、耗时 1151→965 ms，约省 15%。真正的大头仍是「少掉进 full 模式」，这由前面几条节点识别修复来改善；
- [x] 角色池里没有合格一号位时不再拒绝编组（2026-09-19，按要求）。原先「当前角色池中没有满足生存资格的一号位；不再仅按掩护者职阶强行上T」会直接让第一队建议不可用，可这一战还是得打，结果是有队不发、卡在编组页。现在回落到 Boss 后续队位从 2026-09-17 起就在用的无T阵容：照常组出当前最优的五人队上场，并在理由里写明「按无T阵容上场」，`safeBossTeamCount` 记 0，不会把不达生存线的掩护者悄悄当成合格一号位。失败重试路径同样回落，否则第一次的兜底会在重试时又卡住；已失败过的阵容仍然不会被重复提交。这条兜底只在角色池确实一个合格一号位都没有时触发——池子里有T却组不出队（站位未知、人数不足）仍然报各自原本的具体原因；
- [x] 「移动确认弹窗多次取消无效，请手动关闭后继续」，而且一次取消都没点（2026-09-19 反馈，无日志）。取消次数上限 3 次是为了「按不动的弹窗就别一直按」，但那个计数器只在会话出错重走登录时才清零，等于变成了整局上限：一局里前三个误触弹窗即使每次都成功取消，第四个也会直接走到「多次取消无效」分支，连点都不点。已改为弹窗连续消失 3 帧后归还预算——画面清空本身就证明上一次取消生效了；要求连续多帧是为了不把淡出的单帧或反复闪烁当成证明。弹窗真的按不动时的原有行为不变；
- [x] 批次刚开游戏就报 CLIENT_INVALIDATION_FAILED（2026-09-19 调试包 192858）。这一轮开始时游戏还在启动，抓到的每一帧都是竖屏 1080×1920 的桌面，全部锚点 0 分。识别回调在「游戏不在前台」时会直接返回、不读页面，所以预检读到的一直是初始值 UNKNOWN；而 UNKNOWN 的另一层含义是「黎明界页面没认出来」，那是需要失效旧会话的，两种情况分不开、后者赢了。于是终止器在游戏根本没画东西的屏幕上找了 5 秒的「黎明界」触发锚点，超时后整批停在 CLIENT_INVALIDATION_FAILED。已加入「是否真的看到过客户端」这一独立信号：预检前先等客户端到前台（最多 20 秒，冷启动够用），仍然没看到就跳过失效步骤并拉起客户端，交给入口导航——没有客户端在屏幕上，本来就没有旧会话可失效；
- [x] 开局角色可自定义（2026-09-19）。策略设置新增「开局」分组，可按公会改三个必选槽位；每槽可以排多个候选，程序按顺序取第一个能在列表里找到的，全部找不到时照旧停机不随机补人。公会附赠角色由游戏决定，不可编辑。配置存在 `LabyrinthStrategySettings.openingRosters`（公会 ID → 三个槽位的角色 ID 列表），随策略一起持久化；校验不过的方案整条忽略、回落到内置方案，避免半套开局被静默执行。未配置的公会保持内置方案不变；
- [x] 咲恋救济院内置开局改为 女仆（铃莓 1025）／花女仆（铃莓·春日 1308）／水电（咲恋·夏日 1103）（2026-09-19，按要求）。三个昵称都由随包别名表唯一确定。原先该公会第三槽带 6 个备选，现在三槽各一名；`LabyrinthDecisionPolicyTest` 里验证「第2顺位替代」的用例改用仍有备选的王宫骑士团；
- [x] 筛选后剩下的唯一一张卡识别不出来（2026-09-19 调试包 192229）。卡片完整、明亮、没有遮挡，但识别器连槽位都没检测到。网格检测先按像素测出卡片所在的列，再按列间距把它外推成一整排 8 列供几何对齐用；问题是行投影也拿这排外推列当证据，而行得分取的是「最强的两列」的平均。编组页的「用角色名搜索」输入框正好在扫描视口里，横跨好几个外推列，于是它把行投影的峰值抬到 1.0，阈值 0.45 就压过了唯一那张真卡的 0.477，整帧一行都测不出来。已改为只让真正测到的列参与行投影，外推列仍只用于几何。同一帧现在能正确定位并识别出该角色（七七香·万圣节），多卡帧不受影响；
- [x] 有效效果筛选只剩一名角色时的滚动死循环（2026-09-19 调试包 192229）。与之前修过的「零角色」不是同一条：那次靠「未搜索到该角色」提示短路，而这次筛选下确实有一张卡片，只是角色识别器解析不出它，`visibleCharacters` 为空但提示模板不在。于是走到滚动探测，而探测的结束条件是比较滑动前后的头像签名——没有任何头像就没有签名，`unchangedProbes` 永远加不上去。同时这份短列表的滚动条滑块占满整条轨道，`canScroll=false`，边界证据也不可信，每一帧都落到探测分支。结果是每 17 秒把列表倒回顶部一次，一直到会话结束。已给探测加上空视口预算：识别器认为画面已稳定、却连一张卡都解析不出的完整滑动累计 4 次后结束扫描（零个对策角色本身就是答案）。只统计真正完成的滑动，不统计两次滑动之间的重复帧，也不统计未稳定帧，所以加载慢的列表不会被提前截断；
- [x] 已建档的 EX 被报成「没有相关攻略」（2026-09-19 调试包 173956）。攻略表里有流夏的暗影，名字也确实被 OCR 读对了，界面一直显示「正在识别第3只魔物：流夏的暗影」。卡在可信度上：EX 身份要两次独立 OCR 读到同一结果才算可信，而第二次永远不会发生。`RelicOcrScheduler` 有一条公平规则，某个槽位读过一次后，要等所有还没读过的槽位各读一次才轮得到它第二次。挑战页刚出现的几帧里结构检测还没判定是多目标，于是创建了单目标名称槽；判定为多目标之后那条分支再也不跑，那个槽永远停在「没读过」，永久压着详情槽。结果是名字读到了、可信度上不去、超时后报「详情在限定时间内未匹配已知EX」。已把这条优先权改成有时效的：只有仍在被请求的槽位才保留优先权，被放弃超过 1.5 秒的不再阻塞别人；
- [x] 事件后的「无法获得报酬」弹窗仍然不处理（2026-09-18 调试包 201322）。识别其实一直是好的：该帧里确认按钮模板打到 0.985，`labyrinthGenericConfirmDialogRect` 也能正确返回按钮位置。问题在分派：弹窗画在地图之上，页面分类仍然是 NODE_SELECTION，而 `processFrameResult` 对 NODE_SELECTION 帧一律交给节点搜索，只有 `handlePostEntryPage` 里才有按这个按钮的代码。于是弹窗开着，程序在后面滑地图找 普通战斗#40501，滑了 279 次。已在分派处加一条：识别到单按钮确认弹窗时该帧归弹窗处理，模态挡住了一切，背后既点不到也滑不动；
- [x] 节点明明在画面里却识别不到（2026-09-16 调试包 174016）。不是模板不够好，是扫描网格有洞。节点模板的得分峰很窄：在 `node.event.inactive.bottom` 上实测，节点真实位置得分 0.72，偏 8 px 掉到 0.52，偏 28 px 只剩 0.26，而接受线是 0.60——搜索框必须落在节点 ±5 px 内才算得出来。横向偏移表却在 160 到 320 之间一步跨 80 参考像素，局部精修只能走约 ±17 px，于是存在这样的横向条带：节点完整、明亮、没有遮挡地立在画面中央，但每一个被打过分的搜索框都离它 40 px，永远识别不到；地图停在哪个条带由上一次滑动决定，所以现象看起来时有时无。已把偏移表的空洞补到最大 34（补入 136/187/213/267/293 及其镜像）。该帧的识别数从 3 个升到 5 个，两个此前完全看不见的事件节点恢复，原本就识别到的发光遗物节点不受影响，也没有新增误识别。补密只用在没有相机预测的兜底扫描上：定向搜索本来就对准目标且有 800 窗口预算，仍用粗网格，连续两帧未命中会自动落到用密网格的模式。兜底全扫描的窗口数增加约 20%；
- [x] 节点滑丢（2026-09-18）。三处改动：(1) 扫描滑动位移改为按列间距计算（0.8 个列距，1080p 下 432 px），此前是屏宽的 40%（768 px），而实测列间距只有约 513 px（拓扑列 x=533/1057/1559），一次滑动跨 1.4 列，节点可能整个穿过视口不被扫到，扫描器因此报 `geometryReliable=false`、`uncertainSwipes`；(2) 手势末端静止 220 ms 再抬手（`AutomationAction.Swipe.holdMillis`，用 `continueStroke` 续一段 1 px 的尾巴），此前匀速直线走完即抬手，末速 1700 px/s，游戏按甩动处理，地图继续惯性滑行，实际位移不可预测；(3) 局部微调设上限并升级：微调方向是 B/F/F/B 循环，净位移为零，重复再多轮也不可能看到新画面，实机日志里对同一个节点微调了 90 次和 170 次。现在 8 次（两个完整循环）后重新武装整段扫描，最多 3 轮后以明确诊断信息停机；
- [x] App 侧游戏服会话被客户端登录踢掉：客户端每轮返回标题重登都会使 App 的 API 会话失效，下一次读取返回 REJECTED「连接中断。回到标题界面。」。刷开局路径本就会删会话重登重试；「读取当前开局」此前直接落入待验证状态并要求手动「重新验证」，现改为同样删会话、重新登录、重读 top（`labyrinthReadRetriesWithFreshLogin`，上限 3 次）；
- [~] Run-scoped 状态统一 reset：依赖 `stop()` 已有的清理；未单独审计第 13 节清单；
- [ ] Capture lifetime 与 Run lifetime 的自动化回归测试；
- [ ] Android 15 连续多轮实机长测；
- [~] 批量日志与调试包字段：会话重置阶段现已把每帧镜像到网页调试面板（此前该阶段面板停更，因为面板只接单局会话的帧）；终止器每一步写 `LabyrinthReset` 日志。checkpoint 仍未接入调试包导出；
- [x] 连结奖励链未被当作进入凭证（已修复 + 回归测试）；
- [x] 容错期间 pending 转移被页面切换清掉（已修复 + 回归测试）；
- [x] 语义修复误绑单个候选导致点错节点（已修复 + 回归测试）；
- [ ] 节点完成账本（上述三例只是补住已知破口，账本仍未实现）。
- [x] **「遗物效果结果」弹窗识别**（2026-09-23，来源 `upstream-report-20260923`）。该弹窗此前既无模板也不属于任何确认弹窗页，`labyrinthSessionBlockObservation` 会把它误判为会话失效并准备点「返回标题」，属于会直接毁掉整局的误动作。已加入 `entry.relic_effect_result.title` / `entry.relic_effect_result.close` 两个锚点（ROI 取自上游真实 1920×1080 帧），在 `labyrinthGenericConfirmDialogRect()` 的页面状态判断之前加入前置分支直接返回该弹窗自身的关闭按钮，`ITEM_REWARD` 关闭计划也优先使用它；回归用例以上游真实帧为夹具，并断言 `SessionBlockKind.NONE` 且不提供返回标题点击；
- [x] **事件自由选角死循环**（2026-09-23，调试包 `limingjie-debug-20260922-221722`、`limingjie-debug-20260923-013929`）。两包均在事件自由选角页停住约 480～545 秒直到人工结束，界面消息在「等待当前视口角色稳定识别」与「页面识别已更新」之间反复跳动。逐帧还原后真因有三条，全部已修：
  - **选中状态靠记账而非看画面**：候选 ID 在派发点击的瞬间就写进 `eventFreeRoleSelectedCharacterIds` 且此后从不复核。游戏忽略了那一次点击（帧 598 画面 24 张卡、选中 0 张），幽灵选中却让之后每一帧都走确认分支。现在每帧用 `labyrinthEventFreeRoleReconcileSelection()` 以角色列表为准对账：卡在画面上且未选中就丢弃，滚出视口才保留，列表显示已选中的一律采纳；
  - **灰按钮被读成蓝按钮**：启用/禁用两张模板共用同一 ROI、只差颜色，互相之间相关度都很高。该帧 `invite_enabled` 仍有 0.925，单阈值判定通过，于是反复点一个点不动的「去邀请」。改为 `labyrinthInviteButtonState()` 按两张模板的分差定胜负（0.925 对 0.987 → DISABLED），确认分支要求按钮不处于 DISABLED；
  - **看门狗被自己的重试喂活**：选人分支每次派发前都调用 `resetEventFreeRoleProgress()`，45 秒放弃计时器因此永远清零。现在只有「画面上选中人数真的变多」才重置计时；确认分支与「等待按钮就绪」分支也纳入同一计时，超时按既有策略停止本局，不再有无限等待。
  选人策略本身保持与商店一致：只排当前视口、按评分取最高，不翻页、不搜索队伍。
- [x] **「返回标题」要求连续帧**（2026-09-23，雷电 9 小米客户端实测）。失效恢复原先用 `sessionBlockStableFrames` 计数，它把「按钮还没识别到、只在等待」的阻塞帧也算进去，门槛早被垫满，于是 `session.return.title` 单帧越过 0.45 就立即点击。实测黎明界商店页（白底面板、右下「关闭」）被判为失效阻塞，单帧打出 0.646 与 0.524。新增 `sessionReturnTitleStableFrames`，只累计**连续识别到按钮**的帧（`labyrinthSessionReturnTitleStreak()`），任一帧没识别到即清零；真正的失效弹窗按钮持续存在，恢复不受影响。
- [x] **无障碍手势回调拖住整局对象导致跨局 OOM**（2026-09-24，雷电 9 / Android 9 实机堆转储）。连跑第 4 局开局 20 秒报 `Failed to allocate … max allowed footprint 402653184`，停机两分钟后 Java 堆仍有 248 MB。堆转储：1136 个 `GestureResultCallbackInfo` 留在 `AccessibilityService.mGestureStatusCallbackInfos` 里（约等于 4 局发出的全部手势，均已报告完成），回调捕获的 continuation 经协程链拖住 `LabyrinthNodeSession`，共 7 套 `NodeTemplateSet`（每套 25.6 MB）。Android 9 在回报手势结果后不移除回调。改为回调只持有一个 `AtomicReference`，完成、取消、被系统拒绝、协程取消（含 `withTimeoutOrNull` 超时）时清空。修复后连续 12 局以上，局末 Java 堆稳定在约 38 MB，单局最大增量 +0.3 MB。
- [x] **「破晓之星」星辉吐息复活后的两键战斗失败页无法识别**（2026-10-04，用户实机日志包 `limingjie-debug-20261004-172026`）。星辉吐息把败北改判为胜利后，普通/EX 战斗失败页只画「伤害报告 + 下一步」两颗按钮、左下没有「结束」，而 `LabyrinthBattleFailureDetector` 把「结束」当必需闸门 —— 闸门为 0 时 `detect()` 返回 null，且 `BATTLE_FAILED` 只有这一个生产者（分类器 `stateScores` 里没有该键），整帧因此失去页面归属判 UNKNOWN、动作被禁用，随后被战斗等待或停滞看门狗停机。修法两处：`vision` 侧把「结束」从必需闸门改为布局选择器（`THREE_BUTTON` / `TWO_BUTTON`，`endButtonRect` 变可空），并加「两键变体要求右上『伤害报告』同时在线」的守卫来挡掉遮罩与淡入中间态 —— 阈值与 ROI 一字未改，三键帧的置信度与两个点击矩形逐位不变；`session` 侧新增 `BATTLE_FAILURE_NEXT`，两键失败页只推进（重置战斗等待与帧计数）、不消耗重试预算、不清失败队伍签名与已提交队伍，三条停机条件（3 次失败重刷 / 批次重试上限 / 重试上限）仍只作用于三键页。验证：`gradle :app:testDebugUnitTest` 145 套件 / 1080 用例 / 0 失败 / 0 错误 / 39 跳过；真机证据两部分 —— 已入库的 1920x1080 真机两键图（`test/resources/labyrinth/battle-failure-two-button-20261004.jpg`，`end=0.000` / `retryBlue=0.934` / `reportLegacy=0.770` / `titleBlue=0.349`，判 `BATTLE_FAILED / TWO_BUTTON`）与未入库的实机日志包归档帧（两键失败页 → 下一步 → 遗物效果结果 → 关闭 → 回地图全程走通）。边界：Boss/EX 路径、失败后重刷开局未做真机验证，两键变体的触发条件目前只有领域描述（星辉吐息整局一次、Boss 战不触发）；为复现该页临时加过的「强制最低配合」测试通道已随本次改动一并移除，其本身未做真机验证；详见 android/docs/relic-revive-battle-failure-audit.md；

---

## 20. 最终架构图

```text
┌─────────────────────────────────────────────┐
│                 用户一次设置                │
│ 公会目标 / 次数 / 难度 / 失败策略            │
└───────────────────┬─────────────────────────┘
                    │
                    v
┌─────────────────────────────────────────────┐
│       MediaProjectionCaptureService         │
│ Android 15：整个 Batch 只授权/创建一次       │
└───────────────────┬─────────────────────────┘
                    │ frames
                    v
┌─────────────────────────────────────────────┐
│             LabyrinthBatchController        │
│                                             │
│  goals / checkpoint / current run / outcome │
└───────┬────────────────┬────────────────────┘
        │                │
        │ start run      │ reroll
        v                v
┌────────────────┐  ┌─────────────────────────┐
│ Recognition    │  │ LabyrinthRerollWorkflow │
│ Session        │  │ + RerollService         │
│                │  │                         │
│ 单局游戏流程    │  │ 服务端刷开局             │
└───────┬────────┘  └────────────┬────────────┘
        │ terminal event          │ success
        └──────────────┬──────────┘
                       v
             ┌───────────────────────┐
             │ GameSessionReset      │
             │ Planner / Executor    │
             │                       │
             │ 使旧客户端会话失效     │
             │ → 标题 → 主页 → 冒险   │
             │ → 黎明界               │
             └───────────┬───────────┘
                         │
                         └──────> 下一 Run
```

---

## 21. 验收标准

“最终全自动”完成的定义不是单局再次通关，而是满足以下全部条件：

1. Android 15 上只进行一次 MediaProjection 授权；
2. 同一批次只创建一次 VirtualDisplay；
3. 用户设置至少两个不同公会、多个目标轮次后可以离开不管；
4. 单局正常通关后自动刷下一开局并回标题；
5. 单局战斗达到失败阈值后自动放弃并刷下一开局；
6. 两条路径都能重新进入标题→主页→冒险→黎明界流程；
7. 下一局不会继承上一局节点、角色、编组或重试状态；
8. 目标次数统计正确且可恢复；
9. 未知状态不会盲点；
10. 连续至少 20 轮测试无人工干预、无 Capture 重授权、无跨局状态污染；
11. 出错后调试包可以明确定位停在哪个 batch / goal / run / stage；
12. 全部目标完成后才主动结束 Capture Service 并给出批次汇总。

达到以上条件后，项目才从“单局黎明界自动化”正式变成“可长期无人值守的黎明界批量执行器”。

