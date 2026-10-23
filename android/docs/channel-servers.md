# 渠道服与按账号选择服务器

国服有两套互相独立的游戏服务器：B 服，和各联运渠道（小米、华为、vivo 等）共用的渠道服。两者网关、资源密钥、PLATFORM-ID 都不同，账号不互通。渠道服之间只有客户端包名不同。

## 服务器表（`protocol/bilibili/GameServer.kt`）

| 存储 ID | 服务器 | 客户端包名 |
|---|---|---|
| `cn-bilibili` | 国服 Bilibili | `com.bilibili.priconne` |
| `cn-xiaomi` | 小米渠道服 | `com.bilibili.priconne.mi` |
| `cn-huawei` | 华为渠道服 | `com.bilibili.priconne.huawei` |
| `cn-vivo` | vivo 渠道服 | `com.bilibili.priconne.vivo` |
| `cn-oppo` | OPPO 渠道服 | `com.bilibili.priconne.nearme.gamecenter` |
| `cn-aligames` | 九游渠道服 | `com.bilibili.priconne.aligames` |
| `cn-4399` | 4399 渠道服 | `com.bilibili.priconne.m4399` |
| `cn-mumu` | MuMu 渠道服 | `com.bilibili.priconne.yofun.mumu` |
| `cn-tencent` | 应用宝渠道服 | `com.tencent.tmgp.bilibili.priconne` |

存储 ID 写进账号表 `serverId`，发布后不可改名。`AndroidManifest.xml` 的 `<queries>` 必须覆盖全部包名（`GameClientResolverTest` 断言）。

## 登录

| | B 服 | 渠道服 |
|---|---|---|
| 游戏服网关 | `l3-prod-all-gs-gzlj.bilibiligame.net` | `l1-prod-uo-gs-gzlj.bilibiligame.net` |
| RES-KEY | `ab00a0a6…` | `d145b290…` |
| PLATFORM-ID | `2` | `4` |
| 第一层登录 | Bilibili SDK（账号密码、验证码） | 无；账号页的「登录账号 / 密码」即 `uid` / `access_key`，直接进入游戏服 `tool/sdk_login` |

来源：cc004/autopcr `autopcr/sdk/sdkclients.py` 的 `bsdkclient` / `qsdkclient`。`sdk_login` 的字段与 B 服相同，未做改动。

- 渠道服账号永不创建 Bilibili SDK 对象；游戏服要求风险验证时直接失败，不回落到 B 服验证码（`BilibiliNativeLoginCoordinatorTest`）。
- `DEVICE-ID = md5(uid)`。
- 渠道服的 `access_key` 保存时去掉首尾空白；B 服密码原样保存。

## 选择要操作的客户端（`automation/GameClientResolver.kt`）

| 选中账号 | 结果 |
|---|---|
| 服务器已知，客户端已安装 | 操作该客户端 |
| 服务器已知，客户端未安装 | `NotInstalled`，拒绝自动化，不换到别的渠道 |
| 服务器值读不懂 | `UnknownServer`，拒绝自动化，不按已安装的包猜 |
| 没有选中账号 | 只装了一个客户端时用它；零个或多个时拒绝 |

- 无障碍后端、前台在场检测、启动与重启客户端都在每次使用时读取目标包名，切换账号即切换目标。
- 解析不出目标时，前台校验收到一个永不匹配的占位包名 `com.landosol.toolbox.no-game-client`，所有动作被拒绝。`AndroidAccessibilityActionBackend` 的目标包名是非空类型，没有「不校验前台」这一档。
- 启动 Intent 去掉 `package` 字段：带着它时 `Intent.filterEquals` 与桌面启动器建的任务根不相等，系统会在现有任务上再建一个 SplashActivity，渠道客户端的 PermissionActivity 随后永久盖住 MainActivity（黑屏）。
- 启动闸门按目标游戏包是否已在前台判断；已在前台时直接接管识别，不重发启动 Intent。无障碍记录的 Activity 名可能停留在已经关闭的 SDK 公告页，不能要求它等于 MainActivity，否则冷启动导航完成后会拒绝启动单局（2026-09-26 日志包 183318）。接管后的点击仍受页面识别与前台包校验约束。

## 账号表里的服务器

- 新增账号必须选择服务器，没有默认值。
- 读不懂的 `serverId`（新版本写入、手工改库）显示为「未知服务器（原值）」，不登录、不自动化；编辑时必须重新选择服务器并重填密码。
- 更换服务器必须重填密码，并清除该账号的 SDK 会话与游戏会话。界面与仓库写入共用 `accountServerChangeRequiresPassword()`。

## 渠道 SDK 登录界面（`automation/session/ChannelLoginPrompts.kt`）

回到标题后，部分渠道 SDK 会先弹出自己的安卓原生界面，Unity 画面模板认不出它们。两个流程都会每 500 ms 用无障碍读一次窗口树，按文字推进（不依赖画面帧，竖屏的 SDK 页也能处理）：

- 会话重置：`WAITING_LOGIN` 阶段轮询；开始时如果已经看到渠道登录界面，说明客户端还在登录，跳过「触发会话失效」直接进入 `WAITING_LOGIN`（否则页面判 UNKNOWN，会去触发会话失效并超时，整次重置在到达 `WAITING_LOGIN` 之前就失败，2026-10-12 诊断包即如此）。
- 入口流程（从标题页开始的单局 / 批量首轮）：入口阶段一直轮询，交接给路线执行后停止。没有这一步时，入口规划器会把渠道对话框当成公告前画面，在对话框外连点 (950,930)。

规则：

| 渠道 | 界面 | 认定条件（同一窗口） | 点击 | 窗口包名 |
|---|---|---|---|---|
| 九游 | 登录历史（横屏对话框） | 「登录历史」 | 「最近登录」所在的账号条目 | 九游客户端 |
| vivo | UnionActivity 账号页（竖屏网页） | — | 不点，SDK 约 4 秒后自动登录并转回横屏 | — |

4399 曾尝试同样按文字点「用户协议及隐私政策」「账号授权」，实机无障碍未匹配到协议框（4399 SDK 使用 WebView，未取得控件树定位），已删除；4399 改为刷开局时重建任务栈（见下节），以避免走会触发 SDK 登出的「返回标题」路径。

- 规则按选中账号的服务器取；其他渠道没有规则，什么都不点。
- 目标文字必须完全相等且整窗唯一，所以「登录其他手机号」这类按钮永远不会被点。
- 同一界面连续两次轮询都认到才点；同一规则两次点击至少隔 4 秒，最多 3 次，用完仍在就停止会话重置并写明原因。
- 点击前重新读一次窗口复核：活动窗口仍属于该包、同一规则仍认得出（标题仍在、目标仍唯一）、目标与点击区域位置都没变；任何一项变了就不点，从头重新确认（不计入点击次数）。
- 点击方式：目标可点就对它发 `ACTION_CLICK`；不可点时找往上至多 3 层、包住目标且内部没有其他可点节点的祖先（九游「最近登录」所在的账号条目），记下它在目标上面第几层。点之前按文字和位置重新找到唯一的活节点，再往上走同样的层数，那个节点必须仍在原位置且可点才点（同框的不可点文字不会挡住它），否则不点。没有这样的祖先或 `ACTION_CLICK` 被拒绝时才发手势。节点点击和手势都走流程自己的 `SessionBoundActionExecutor`（任务会话、暂停与其他入口动作相同，手势另有前台包校验；演练会话不真点）。
- 认到界面后 1.5 秒内，画面识别既不判阻塞弹窗，也不让入口规划器点击（九游对话框曾被当作公告前画面，一直点对话框外的 (950,930)；入口流程「返回标题」的点击点 (961,739) 也可能落在对话框上）。
- 日志：`LabyrinthReset: channel-prompt tap rule=… target=… pkg=… at=x,y attempt=n`，以及 `result=Done(via=click|gesture)/Gone/Paused/Refused(…)`；复核不通过记 `channel-prompt skip rule=… reason=…`。

## 渠道客户端任务栈重建（4399 / 应用宝 / MuMu / 九游）

助手刷开局要用同一账号登录游戏服，会把客户端顶号。客户端随后遇到 6002 点「返回标题」时，部分渠道 SDK 会登出：4399 重新弹用户协议并要求账号授权（2026-10-14 诊断包：`Back_To_Title` → `operate User not Valid` → 协议框），应用宝、MuMu 要求重新输入账号密码。对 4399、应用宝、MuMu 和九游，刷开局前先将游戏退到后台；刷完后按账号服务器包名显式启动 `com.bilibili.priconne.bili.SplashActivity`，并设置 `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`，清除旧 Activity 任务栈后从启动 Activity 进入。

- 任务重建启动 Intent 的 `ComponentName` 包名按当前账号渠道切换（例如 MuMu 为 `com.bilibili.priconne.yofun.mumu`），Activity 类名使用 `com.bilibili.priconne.bili.SplashActivity`。
- 这**不是进程终止**，不清数据，也不保证应用进程退出或 SDK 一定保持登录；`CLEAR_TASK | NEW_TASK` 只重建 Activity 任务栈。Android 版本不再影响这条路径，不调用 `killBackgroundProcesses`，Manifest 也不需要 `KILL_BACKGROUND_PROCESSES` 权限。
- 批量流程仍检查重建后的识别画面：需要先看到标题页；连续两帧先识别到游戏内页面则停止，避免直接接管旧局状态。该检查只能验证可见启动流程，不能证明进程已终止。
- 一次性任务重建凭证绑定账号 ID 和游戏包名。每次刷开局开始、批量开始与停止时清空；同步客户端时无论成功与否都先取走，只有账号和包名都匹配才使用任务重建路径，避免凭证串到另一账号或渠道。
- 覆盖三条刷取路径：批量刷开局、批量复用已读取开局前的复核、单独刷开局（含战斗失败后自动重刷）。
- 批量在会话重置流程中启动 SplashActivity，再走标题 → 公告 → 主页 → 冒险 → 黎明界；单独刷开局结束后启动相同任务重建 Intent（没有画面识别，由用户接手）。
- 其他渠道保持原来的会话失效方式。
- 日志标签 `LandosolClient`：`task-reset home`、`task-reset backgrounded`、`刷开局前已准备重建任务栈`、`任务重建凭证 账号=… 包=… 有效=…`、`刷开局结束，已通过 CLEAR_TASK | NEW_TASK 重建任务栈`。

## 屏幕临时旋转

vivo 账号页、4399 账号授权页会让显示器临时转成竖屏。只对选中账号是 vivo、4399、九游（九游的测试在此设置下通过）时生效（`ChannelSdkTraits.toleratesTransientRotation` → `CaptureRotationPolicy`），其他服务器保持上游「尺寸一变就停止」。生效时捕获层不再一旋转就停止：显示器尺寸恰好是捕获尺寸的转置时，保留原横屏 VirtualDisplay、丢弃旋转期间的画面，转回原方向 1 秒后恢复投递。竖屏超过 120 秒，或尺寸变成别的分辨率，仍按原来的方式停止捕获并要求重新授权。日志标签 `LandosolCapture`。

## 已知限制

- 运行中编辑选中账号的服务器会改变点击目标。请在自动化停止时编辑账号。
- 渠道 SDK 登录界面的规则只来自录屏和调试包截图，节点文字与可点击属性尚未在设备上用无障碍树核对；SDK 改版换了文案就会认不到（认不到时不点，入口流程照旧超时停止）。
- 九游「登录历史」点的是上次登录的账号，不核对它是否就是助手里选中的账号。
- 小米客户端冷启动时 SDK 登录会让标题页停留 10 秒以上，标题页点击间隔为 5 秒。

## 实机验证

雷电模拟器 9，1920 × 1080：小米、华为、vivo 渠道服登录、刷开局与路线执行通过；4399 可以登录。九游、vivo 测试员报告登录修复可用。4399 改为刷开局前准备任务栈重建后，测试员实机确认通过。应用宝、MuMu、九游的任务栈重建尚未实机验证。OPPO 未验证。B 服登录路径与 main 相同，只是游戏包名改为按账号解析，未用 B 服账号回归。
