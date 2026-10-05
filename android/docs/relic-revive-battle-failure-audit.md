# 遗物复活后战斗失败页卡住：根因核实与实现级设计

> **落地状态（2026-10-04 复核）**：§3 的两处实现改动 —— 检测器（`LabyrinthBattleFailureRecognition.kt` 的
> `LabyrinthBattleFailureLayout` + 三道必需闸门 + 「两键变体要求右上伤害报告同时在线」的守卫）与
> 动作层（`LabyrinthPostEntryActionKind.BATTLE_FAILURE_NEXT` + 会话层三处分流）——**都已落地**；
> §3 的后续链路与优先级两小节未被改动（弹窗仍由既有 generic-confirm 分支接管）。
> 因此 §3 是**设计原文**（保留用于追溯「为什么这么做」），不是待办。
>
> **真机验证（2026-10-04 日志包）**：用户 17:20 导出的实机日志包跑的是**带修复的构建**，
> 完整走通了 `战斗失败页（两键）→ 下一步 → 遗物效果结果 → 关闭 → 回地图`，证据与边界见 **§6**。
> 也就是说：**两键失败页现在在真机上能被识别并推进**；§1.4 描述的看门狗停机是**修复前**的行为。
>
> **用户 1920x1080 真机两键截图已入库**：`android/app/src/test/resources/labyrinth/battle-failure-two-button-20261004.jpg`
> 是**可入库的主基准 fixture**，§5 表格的第 1–4、8 项已由它定案（实测数字见 **§6.1**）。
>
> **本修复不依赖任何测试通道开关。** 协作期间为复现该页做过一个临时的「强制最低配合」测试通道，
> 它已随本 PR 一起删除：`strategy-settings.md` 的测试通道章节已移除，生产侧四个文件与两个测试文件
> 都回到 1.0.15 原状（`git diff b8d8af9 -- <这 6 个路径>` 为空），没有留下任何开关字段、快照分支或
> 一次性解除武装的回调。识别与动作层的修复与它无关。
>
> 范围：本文件只做核实与设计记录，不改生产代码。
> 可执行证据：`android/app/src/test/java/com/landosol/toolbox/labyrinth/vision/LabyrinthBattleFailureLayoutAuditTest.kt`
> （12 个用例，逐条对应本文结论）。
>
> 用户 2026-10-04 截图 `android/app/src/test/resources/labyrinth/battle-failure-relic-revive-20261004.jpg`
> （2848x1320）**不入库**，只在本机作为参考；测试用 `assumeTrue` 读取它，缺失时自动跳过而不是失败。
> 本机另有独立测量脚本 `.toolchain/scratch/Probe.java`（本机脚本目录，不入库），
> 用 `java .toolchain/scratch/Probe.java <图片> <ascii|strip|rows|box|cov|row>` 可以脱离 Gradle
> 复算本文里的任何覆盖率与包围盒。

---

## 0. 结论摘要

| # | 结论 | 证据 |
|---|---|---|
| 1 | `BATTLE_FAILED` 在整套代码里**只有一个生产者**：`LabyrinthEntryFrameProcessor.processPrepared` 在 `battleFailureDetector.detect(frame) != null` 时把状态覆盖上去。分类器 `LabyrinthEntryPageClassifier` 永远不会输出它。 | 单测 `the classifier alone can never produce BATTLE_FAILED`：分类器 `stateScores` 里没有 `BATTLE_FAILED` 键；指纹图原始分类结果 = `UNKNOWN 0.0` |
| 2 | 两键截图失败在**且仅失败在「结束」闸门**：`end=0.000`，其余 `retry=0.912 reportLegacy=0.779 title=0.341` 全部过线。 | `two button capture fails the end gate and nothing else`（断言 fail 集合恰为 `[end]`） |
| 3 | 右下蓝色按钮在两种布局下**同一处**：把 2848x1320 帧按 `ReferenceFitMapper` 归一化后，按钮实测范围 `[1412,943..1823,1030]` 对照 1920x1080 参考帧 `[1412,945..1823,1033]`，四边偏差 ≤3px。 | `the lower right button sits at the same reference position in both layouts` |
| 4 | 该 2848x1320 帧**没有被外部裁切**，也**没有各向异性（非等比）缩放**；三个独立地标在零参数 FIT 预测下偏差 0–4px，隐含水平偏移 250.22/250.56/251.11 对照预测值 250.667。等比重采样在几何上不可判定（与 FIT 可交换），但对本设计无影响。 | `the local capture is not displaced by an external crop or an anisotropic rescale` |
| 5 | 最小可靠区分依据 = **左下浅色按钮槽位的有无**：1080p 参考帧 `end=0.895`，两键帧 `end=0.000`，阈值仍用既有 `minEndLightCoverage = 0.60`。 | `the empty lower left slot is the minimal two-button discriminator` |
| 6 | 把「结束」从必需闸门改为布局选择器后，`test/resources` 下 54 个图片文件里 **53 张已入库素材逐帧判定不变**；唯一新增命中就是那张本机的两键截图。 | `relaxing the end gate admits no existing fixture` |
| 7 | 遗物效果结果弹窗**不需要动会话代码**：`labyrinthGenericConfirmDialogRect` 的前置分支已经命中，返回的正是弹窗自己的「关闭」矩形。 | `relic effect result popup is closed by the existing generic confirm branch` |
| 8 | Boss 结算路径结构上抢不了这一帧：`labyrinthBossSettlementNextButtonRect` 要求 `pageState == UNKNOWN`。 | `boss settlement next button cannot claim a failure page` |

**复现命令**（Windows / 本仓库沙箱，已验证）：

```powershell
$root='D:\limingjie-assistant'
$env:GRADLE_USER_HOME="$root\.gradle-local"; $env:ANDROID_HOME="$root\.android-sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; $env:ANDROID_USER_HOME="$root\.android-user"
$env:JAVA_HOME='D:\JAVA\jdk-17.0.1'; Remove-Item Env:\ANDROID_SDK_HOME -ErrorAction SilentlyContinue
& "$root\.toolchain\gradle-8.7\bin\gradle.bat" -p "$root\android" :app:testDebugUnitTest `
    --tests "*LabyrinthBattleFailureLayoutAuditTest*" `
    -Pkotlin.compiler.execution.strategy=in-process --console=plain
```

测试用 `println("[battle-failure-audit] …")` 输出全部实测值；JUnit XML 在
`android/app/build/test-results/testDebugUnitTest/TEST-*LabyrinthBattleFailureLayoutAuditTest.xml`
的 `<system-out>` 里，本文所有数字都取自它。

---

## 1. 机械解释：`end` 闸门为 0 时到底发生了什么

### 1.1 唯一生产者：`BATTLE_FAILED` 不是分类器的输出

`android/app/src/main/java/com/landosol/toolbox/labyrinth/vision/LabyrinthEntryClassifier.kt:141-293`
构造的 `stateScores` 里**没有 `BATTLE_FAILED` 这个键**，`classify()` 的 355-357 行只会返回
`UNKNOWN` 或某个有键的页面状态。整个 `android/app/src/main` 中唯一把 `BATTLE_FAILED` 写进
`LabyrinthEntryPageObservation` 的地方是：

```
LabyrinthEntryFrameProcessor.kt
 :500  battleFailure = battleFailureDetector.detect(frame)
 :503  battleEndConfirmation = if (battleFailure != null || classified.state == UNKNOWN) { … }
 :508  observation = battleFailure?.let { failure ->
 :509      classified.copy(
 :510          state = LabyrinthEntryPageState.BATTLE_FAILED,
 :511          confidence = failure.confidence,
 :512          stateScores = classified.stateScores + (BATTLE_FAILED to failure.confidence),
 :514          reason = null,
 :516  } ?: classified
```

也就是说：**`detect()` 返回 null ⇒ 整帧没有任何途径具有 `BATTLE_FAILED` 身份**。实测：
在 1920x1080 参考帧上，把处理器拿到的 `anchorScores` 重新喂给分类器，得到
`raw-classifier verdict=UNKNOWN confidence=0.0`——战斗失败页的原始分类结果本来就是 UNKNOWN，
它的页面身份完全来自上面这段覆盖。

### 1.2 提前返回路径

`LabyrinthBattleFailureRecognition.kt:44-49`：

```kotlin
if (
    endLight   < minEndLightCoverage   ||   // 0.60
    retryBlue  < minRetryBlueCoverage  ||   // 0.60
    !reportMatched                     ||   // legacy≥0.48 或 current≥0.55
    titleBlue  < minTitleBlueCoverage       // 0.18
) return null
```

四个条件是与关系，任意一个不成立就 `return null`。**任何一道闸门为 0 都等价于整页失去归属**，
不是只有 `end` 特殊；本 case 恰好是 `end` 命中而已。

### 1.3 两帧实测（**修复前**的闸门读数；阈值与 ROI 至今未变）

| 闸门 ROI（1920x1080 参考系） | 1920x1080 参考帧 | 2848x1320 两键帧 |
|---|---|---|
| `title` (740,40,450x130) isBlue | 0.343 | 0.341 |
| `reportLegacy` (1600,55,220x45) isPale | 0.771 | 0.779 |
| `reportCurrent` (1380,250,380x55) isBlue | 0.000 | 0.001 |
| **`end`** (1010,965,260x55) isPale | **0.895** | **0.000** |
| `retry` (1480,965,260x55) isBlue | 0.823 | 0.912 |

- 参考帧：四道全过 → `BATTLE_FAILED`，`confidence = (0.895+0.823+0.771+0.343)/4 = 0.7080125258222605`。
- 两键帧：`reportMatched` 由 `reportLegacy=0.779` 满足，`title/retry` 满足，**只有 `end` 为 0**
  → 修复前 `detect()` 就此返回 null → 处理器 `state=UNKNOWN, confidence=0.0, battleFailure=null`
  （当时的打印：`two-button processor verdict=UNKNOWN battleFailure=null`）；
  修复后同一帧被判 `BATTLE_FAILED / TWO_BUTTON`，见 §6.1 与附录 A.2。

注意 2848x1320 帧的 `retry=0.912` 比参考帧 `0.823` **更高**：因为按钮被整体放大
（归一化后 412x88 对参考 412x89），而采样窗 `(1480,965,260x55)` 的映射框固定，落在按钮纯色区
的比例更高、压到「重新挑战/下一步」字形的比例更低。这一点本身也证明该帧的右下按钮
与参考帧同一槽位、同一尺寸（见 §2.2）。

### 1.4 失去归属之后的停机路径（两条，都会停）

> **本节描述的是修复前（`end` 仍是必需闸门时）的行为。** 2026-10-04 的实机日志证明带修复的构建
> 不再走这两条看门狗：两键页被判 `BATTLE_FAILED`，动作标签是 `战斗失败页(遗物复活)：下一步`（§6）。

`LabyrinthEntryRecognitionSession.onFrame` 的顺序（行号取自 `LabyrinthEntryRecognitionSession.kt`）：

1. `:1999 handleSessionBlockFrame` → 不阻塞（见 §3.2）。
2. `:2138 if (!dryRun && entryPhaseComplete && handleBattleWaitFrame(...)) return`
   - 若 `LabyrinthBattleWaitPolicy.startedAt != null`（本局战斗已开始过），
     `LabyrinthBattleWaitPolicy.kt:32-36` 把 `UNKNOWN` 列为等待页 → `WAIT`，帧被吞掉，
     界面显示「等待战斗结算（最长5分钟）」，并在 `:50` 的 `elapsed >= 300_000L` 后
     `:3897 TIMED_OUT` → `finishFromPlanner("等待战斗结算超过5分钟，已停止并保留诊断状态")`。
   - 若本局没有战斗上下文（重开自动化/中途接管），`startedAt == null` → `NONE`。
3. 落到 `handlePostEntryPage`：`pageState == UNKNOWN` 在 `:4964-4995` 的 `when` 里除
   `labyrinthHasBattleControls` / Boss 结算 / 最终结算动画外全部 `null` → `plan == null`
   → `:5333 traceReject("no-plan:UNKNOWN")` → 消息「未知页面，自动点击已暂停；等待页面识别恢复」。
4. 同一条消息持续 `POST_ENTRY_STALL_TIMEOUT_MILLIS = 120_000L`（`:404`）后，
   `labyrinthPageStallExpired`（`LabyrinthPageRuntimePolicy.kt:351-366`；`UNKNOWN` 不是
   `battlePage`，因此不豁免）在 `:5389` 触发
   `finishFromPlanner("UNKNOWN 页面连续120秒无进展：…；已停止并保留诊断状态")`。

**这就是「卡住」**：不是某一个按钮点错，而是整帧没有页面归属 → 动作被禁用 → 看门狗停机。
用户报告的现象（战斗失败页无法识别导致卡住）与 1.4 的两条路径完全一致。

### 1.5 为什么「结束缺失」等于「两键变体」，而不是「不可信页面」

三条独立理由：

1. **位置是同一槽位。** 右下蓝色按钮在两帧归一化后四边偏差 ≤3px（§2.2）。如果这一帧是
   「UI 没画完 / 裁切错位 / 不可信」，右下按钮不可能精确落在 16:9 参考布局的同一格上。
2. **同一页面的其余 UI 全部在位。** `战斗失败` 标题 0.341 vs 0.343、`伤害报告` 按钮
   0.779 vs 0.771，且归一化框逐位相同（标题 `[724,39..1195,160]` vs `[724,39..1197,164]`，
   伤害报告 `[1541,49..1867,101]` 与参考帧**完全相同**）。页面身份的全部正面证据都在。
3. **左下槽位是「空」而不是「暗」或「被别的控件占据」。** 两键帧在该槽位既没有 pale 也没有
   blue（实测窗口 `(1425,1119,438x188)` 内 blue 框为 `null`），窗口内唯一的浅色内容是左上角
   一块 `[1445,1121..1541,1165]`（97x45）的背景亮点，其下边界 1165 距离采样窗上边界 1179
   还有 14px，**完全落在采样窗之外**。即：**控件不存在**，不是「颜色变了」。
   （用户表述「结束这一颗根本不存在」与像素一致。）

结论：`end` 闸门为 0 表达的是「这一页没有『结束』按钮」，而不是「这一页不可信」。
现行实现把这两件事混成了同一个 `return null`，这是根因。

---

## 2. 像素证据

### 2.1 1920x1080 参考帧的三颗按钮（+标题）

用连通性/行程测量（`columnRun` / `rowRun`，阈值「该列/行 ≥25% 像素满足颜色判据」）：

| 元素 | 颜色 | 精确矩形（1920x1080 像素坐标） |
|---|---|---|
| `结束` 按钮 | pale (`r,g,b ≥ 205`) | 列半开区间 `[959,1352)`（393 列），行 `[946,1023)`（77 行）→ 外接矩形 **x=959, y=946, w=393, h=77** |
| `重新挑战` 按钮 | blue (`b≥150, b-r≥35, b-g≥10`) | **x=1412, y=945, w=412, h=89** |
| `伤害报告` 按钮 | pale | **x=1541, y=49, w=327, h=53** |
| `战斗失败` 标题 | blue | x=724, y=39, w=474, h=126（含描边） |

对应采样窗/点击框（保持现状不变）：

| 常量 | 参考矩形 | 相对按钮实测范围的位置 |
|---|---|---|
| `END_BUTTON_SAMPLE` | (1010,965,260x55) | 完全落在 `结束` 按钮实测范围内 |
| `RETRY_BUTTON_SAMPLE` | (1480,965,260x55) | 完全落在 `重新挑战` 按钮实测范围内 |
| `END_BUTTON_RECT` | (945,935,430x110) | 外接于 `结束` 按钮实测范围 |
| `RETRY_BUTTON_RECT` | (1405,935,430x110) | 外接于 `重新挑战` 按钮实测范围 |

### 2.2 右下蓝色按钮「同一处」的证明

把 2848x1320 帧按 `ReferenceFitMapper`（`scale=min(2848/1920, 1320/1080)=1.2222222…`，
`offsetX=(2848-1920*scale)/2=250.667`，`offsetY=0`）归一化回 1920x1080 参考系：

| 地标 | 参考帧 | 两键帧实测 | 归一化后 | 四边最大偏差 |
|---|---|---|---|---|
| 右下蓝色按钮 | `[1412,945..1823,1033]` 412x89 | `[1976,1153..2479,1259]` 504x107 | `[1412,943..1823,1030]` 412x88 | **3 px** |
| 右上 `伤害报告` 按钮 | `[1541,49..1867,101]` 327x53 | `[2134,60..2532,123]` 399x64 | `[1541,49..1867,101]` 327x53 | **0 px** |
| `战斗失败` 标题 | `[724,39..1197,164]` 474x126 | `[1136,48..1711,195]` 576x148 | `[724,39..1195,160]` 472x122 | 4 px |

再加两条只依赖数值、不依赖目视的核对：

- **按钮尺寸比 = 帧高比**：右下按钮宽 504/412 = **1.2233**，`伤害报告` 宽 399/327 = **1.2202**，
  而 FIT 比例 = 1320/1080 = **1.2222**。三者相对偏差 ≤0.16%（高度方向 1.2–1.7%，
  差异来自按钮描边/高光在不同分辨率下的阈值边缘，不作为几何依据）。
- **生产采样窗确实落在按钮实测范围内**：映射后的 `RETRY_BUTTON_SAMPLE` = `(2060,1179,317x68)`，
  被按钮实测范围 `[1976,1153..2479,1259]` 完全包含 —— 这就是 `retryBlue=0.912` 的算术原因；
  同一采样窗在两键帧的**几何位置**与参考帧一致，只是按钮更大、纯色占比更高。

因此「两键版右下 = 三键版右下（重新挑战）**同一处**，只是文字由 `重新挑战` 变成 `下一步`」
这一领域事实，在像素层面成立且可量化。

### 2.3 那张 2848x1320 截图是否被外部缩放/裁切

**结论（置信度：裁切=高、各向异性缩放=高、等比重采样=不可判定且不影响设计）**

1. **无裁切（高置信）。** 用零自由度的假设「16:9 UI 画布按帧高缩放、水平居中」去预测三处
   地标，实测偏差 0–4px。任何水平裁切 δ 会把三处地标同时平移 δ（因为 FIT 的 `offsetX`
   仍按帧宽算），任何垂直裁切 δv 会把 UI 缩放改掉 δv/1320（按钮宽度随之变化 ~5px）。
   实测偏差 ≤4px ⇒ **≳8px 的裁切可排除**；更小的裁切虽然几何上不可见，但对采样窗
   （260x55 级别的窗口、窗内留白几十像素）没有影响。
2. **无各向异性缩放（高置信）。** 两个按钮的宽度比在两帧之间一致到 0.26%
   （参考 1.2599，截图 1.2632），且上述三条地标各自独立地给出同一个隐含偏移
   （250.22 / 250.56 / 251.11 vs 预测 250.667；左对齐=0、右对齐=501.3 都被排除 240px 以上）。
   若是水平拉伸或压缩渲染，这两个比例不可能同时成立。
3. **等比（同宽高比）重采样：几何上无法判定，且与本设计无关。** 设原图为 2848k×1320k 的同
   宽高比截图再缩放，FIT 映射对该缩放可交换，地标仍会落回同一格。可用的弱证据是边缘锐度：
   按钮左边界从暗背景到高光，参考帧跨约 3px（x=1409→1412，像素 `(31,14,46)→(23,17,55)→(67,71,109)→(105,116,148)`），
   截图跨约 2px（x=1975→1977，`(11,13,38)→(97,103,139)→(111,117,151)`）。两者同量级，
   **既不能证明也没有排除**上采样，因此只作旁证，不作为任何结论依据。
4. **文件头旁证。** 两帧都带同一个 `JFIF` + Google sRGB `ICC_PROFILE` 骨架（同一族 Android
   截图/编码流水线），但量化表不同（参考帧以 `%!'&$!…` 开头、截图以 `3*)..$.…` 开头），
   说明编码参数不同（不同机型/不同压缩质量）。两者都没有 EXIF/APP1，也没有可识别的聊天软件
   水印标记 ⇒ **元数据无法定论**。
5. **「地图 HUD」地标不可用。** 战斗失败页是整屏页面，参考帧与两键帧里都**没有**地图 HUD
   （底栏/节点控件）。因此任务里要求的第三个地标无法采集；改用「伤害报告按钮 + 右下按钮 +
   战斗失败标题」三个独立地标替代。这也意味着：**如果该截图是被裁掉了 HUD 区域的局部图，
   本方法无法察觉**——但 §2.3.1 的居中假设与三地标一致已经排除了「裁掉左侧或下侧一大块」
   这类会让 UI 错位的裁切。

**反证条件**（出现任意一条就要推翻上面的结论）：

- 任一地标归一化后偏差 >8px，或隐含 `offsetX` 偏离 `(W-1920·s)/2` 超过 8px；
- 两个按钮的宽度比在两帧间的相对差 >2%（⇒ 各向异性缩放）；
- 按钮左/上边界过渡宽度在截图里 >4px（⇒ 明显上采样）；
- 截图四边出现黑边、状态栏色块或与参考帧不同的留白比例（⇒ 合成/裁切）。

**但无论结论如何：这张 2848x1320 的帧不得作为锚点几何基准**，本文所有坐标都以 1920x1080 参考帧为准，
两键帧只用于「验证上述设计依据能否被满足」。
（用户后来补上的 1920x1080 真机图是另一回事：它**就是**参考系，已入库为主基准，见 §6.1。
两者不能混用——`battle-failure-relic-revive-20261004.jpg` 是本机专用的非 16:9 他人截图，
`battle-failure-two-button-20261004.jpg` 才是入库标准。）

### 2.4 区分两键与三键的最小可靠依据

- **依据**：`END_BUTTON_SAMPLE = (1010,965,260x55)` 上的 pale 覆盖率 `endLight`。
- **阈值**：沿用既有 `minEndLightCoverage = 0.60`，不新增、不放宽任何阈值。
- **实测**：三键 `0.895`（参考帧）/ `0.886`（`ex_battle_failure_20260917.png`）；
  两键 `0.000`。中间是一片 0.60 宽的空白地带。
- **1080p 下的槽位矩形**：左下 pale 按钮实测范围 = `(959,946,393x77)`；采样窗 `(1010,965,260x55)`
  在其内部。两键帧在同一归一化槽位内 pale/blue 覆盖率均为 0（blue 框为空）。
- **合成对照（可入库，随时可跑）**：把 1080p 参考帧的 `END_BUTTON_RECT` 区域用其自身背景
  （取 x=1390 那一列，位于 `结束` 按钮右缘 1352 与 `重新挑战` 按钮左缘 1421 之间）覆盖，
  其余像素逐位不动 —— 三个必需闸门实测**逐位不变**（`retry=0.823 report=0.771 title=0.343`），
  `endLight` 变 0.000，`detect()` 由 `BATTLE_FAILED` 变为 null；提议规则的结论是
  `layout=TWO_BUTTON confidence=0.6458 retry=(1405,935,430,110)`，与三键帧的点击框完全相同。
  这条把「唯一变量 = 左下按钮」钉死在一条可复现用例上。
- 为什么必需的三道闸门里**不能**去掉 `title`：语料里 `boss-win-summary-20260911.png`
  （Boss 战胜结算）`retry=0.827, reportCurrent=0.714, reportLegacy=0.000, title=0.054,
  end=0.000` —— 它同样「没有左下按钮、右下有一颗蓝色『下一步』按钮」，两道闸门都过，
  唯一挡住它的是 `战斗失败` 标题闸门。**标题闸门是承重墙。**

### 2.5 语料库假阳性扫描（`test/resources` 下 54 个图片文件，其中 53 张已入库）

用与生产完全相同的五道 ROI 与同一套判定逐帧测量后：

- 现行规则（四道齐全）命中 **3 个文件**：
  `battle/battle_failure_20260910.jpg`、`battle/ex_battle_failure_20260917.png`(1178x663)、
  `ex/ex_monster_detail_live_20260910.jpg`。后者的 SHA256 与第一者完全相同
  （`5C76BB7B…D47981`），是同一张图的重复存放 ⇒ **其实只有 2 张不同的三键失败页**。
- 提议规则（`title+report+retry` 必需、`end` 作布局选择器，另加「两键变体要求右上『伤害报告』
  同时在线」的守卫，见 §3.1）命中 **4 个**：
  上面 3 个 + 那张两键截图；**两键集合里只有那张截图**。
- 即：**放宽后 53 张已入库素材的判定逐帧不变**（测试断言 `detector` 与提议规则在每张图上
  判定一致，且两键集合恰为空/恰为那一张）。
- 另外两帧值得记录：
  - `battle/ex_end_confirm_choice_20260917.png`（1178x663，失败页 + `结束确认` 弹窗）：
    四道闸门全 0。实测弹窗下 `重新挑战` 按钮像素 = `(33,65,123)`（正常约 `(65,150,240)`），
    即模态遮罩把整页压暗约 50%。这条**排除**了「遮罩帧被误判成两键」的风险：压暗时
    `retry` 与 `end` 一起掉线，`detect()` 仍然返回 null（行为与今天一致）。
  - `labyrinth/boss-win-summary-20260911.png`：见 §2.4，被标题闸门挡住。

---

## 3. 实现级设计

所有改动集中在 3 个文件；`LabyrinthEntryClassifier.kt`、`LabyrinthBattleWaitPolicy.kt`、
`LabyrinthPageRuntimePolicy.kt`、`SessionExpiryFrameTracker.kt` **不动**。

### 3.1 检测器结构 —— `vision/LabyrinthBattleFailureRecognition.kt`

新增枚举与字段：

```kotlin
enum class LabyrinthBattleFailureLayout { THREE_BUTTON, TWO_BUTTON }

data class LabyrinthBattleFailureObservation(
    val confidence: Double,
    val layout: LabyrinthBattleFailureLayout,
    /** 「结束」按钮；TWO_BUTTON 下为 null（该按钮不存在）。 */
    val endButtonRect: EntryPixelRect?,
    /** 右下蓝色按钮：三键=重新挑战，两键=下一步。 */
    val retryButtonRect: EntryPixelRect,
)
```

`detect()` 的改动（其余行、ROI、判据、`SAMPLE_STEP` 全部不变）：

```kotlin
// 必需闸门：只在原来的四条件里删掉 endLight
if (retryBlue < minRetryBlueCoverage || !reportMatched || titleBlue < minTitleBlueCoverage) return null

val endLight = coverage(frame, endSample, ::isLight)
val legacyReportLight = coverage(frame, legacyReportSample, ::isLight)   // 已算过，复用

if (endLight >= minEndLightCoverage) {
    return LabyrinthBattleFailureObservation(
        confidence = (endLight + retryBlue + reportConfidence + titleBlue) / 4.0,   // 逐位不变
        layout = LabyrinthBattleFailureLayout.THREE_BUTTON,
        endButtonRect = endButton,
        retryButtonRect = retryButton,
    )
}
// 两键变体要求右上「伤害报告」同时在线的守卫：普通/EX 失败页的原生两键布局仍画着它，
// 这样淡入淡出中间态（pale 先掉线、blue 还亮着）不会被判成两键。
if (legacyReportLight < minReportLightCoverage) return null
return LabyrinthBattleFailureObservation(
    confidence = (retryBlue + reportConfidence + titleBlue) / 3.0,
    layout = LabyrinthBattleFailureLayout.TWO_BUTTON,
    endButtonRect = null,
    retryButtonRect = retryButton,
)
```

**常量**：`minEndLightCoverage=0.60` 保留（语义从「必需闸门」变为「布局阈值」）、
`minRetryBlueCoverage=0.60`、`minReportLightCoverage=0.48`、`minReportBlueCoverage=0.55`、
`minTitleBlueCoverage=0.18` 全部不变；ROI 常量一个都不改。

**上述守卫的依据与代价**：`isPale` 要求三通道 ≥205，`isBlue` 是色相型判据，因此一次乘性压暗
`d` 存在一个 `d∈[0.625,0.837]` 的窗口：蓝色按钮仍算 blue，浅色按钮已不算 pale。
该守卫用「右上 pale `伤害报告` 必须同时过线」把这个窗口关掉（同一压暗下它也掉线）。
实测两键帧 `legacyReportLight=0.779 ≥ 0.48`，它对两键帧成立。
代价：若将来普通/EX 失败页也把 `伤害报告` 挪到下半蓝槽位（当前只有 Boss 失败页这么画），
两键变体会退化成三键判定 —— 只会导致重试计数多记一次，不会卡住（见 §3.2 的取舍）。
**若将来发现 `legacyReportLight < 0.48`，就去掉这道守卫并记录原因。**

**不变量（1920x1080 三键帧逐位不变）**，可核对的三条：
1. `endLight >= 0.60` 分支里的 `confidence` 表达式与今天**字符级相同**，其余字段只是新增；
2. 既有 `LabyrinthBattleFailureDetectorTest` 的两条断言原样通过：
   `endButtonRect == EntryPixelRect(945,935,430,110)`、`retryButtonRect == EntryPixelRect(1405,935,430,110)`，
   以及 `current lower damage report layout` 的 `confidence >= 0.70`（这两条用例一个字都不改）；
3. 审计用例用**自己重测**的四道覆盖率重建 confidence，与生产 `detect()` 的返回值
   在 `1e-12` 内相等（实测两边都是 `0.7080125258222605`）——任何 ROI/阈值漂移都会让它失败。
4. 语料库回归：53 张已入库素材全部不在新增命中集里（§2.5）。

附带影响（1 行、零逻辑）：`endButtonRect` 变可空后
`SessionExpiryFrameTracker.kt:35` 的 `result.battleFailure?.endButtonRect?.let(::centre)`
天然得到 null，无需改动；见 §3.2。

### 3.2 页面归属与动作 —— 复用 `BATTLE_FAILED`，新增一个动作种类

**归属：继续复用 `LabyrinthEntryPageState.BATTLE_FAILED`，不新增页面状态。**

理由（逐条可核对）：

1. 该页**就是**战斗失败页：同一标题、同一 `伤害报告`、同一阿尔法强化面板、同一右下槽位。
   差异只是「有没有『结束』」与「右下按钮的文字」。
2. `BATTLE_FAILED` 已经被四处按「战斗失败页」接线，新增状态要把这四处全部复制一遍：
   `LabyrinthPageRuntimePolicy.kt:232`（→ `LabyrinthPageUiOwner.BATTLE`）、
   `LabyrinthEntryActionPlanner.kt:395`（开局阶段 → `Complete`）、
   `SessionExpiryFrameTracker.kt:56-57`（失效探针触发页）、
   `LabyrinthEntryRecognitionSession.kt:5281`（动作分支）。零收益、四倍回归面。
3. 差异的正确表达位置是**布局字段**（§3.1 的 `layout`），不是页面状态：识别层回答「这是什么页面」，
   动作层回答「在这一页该按哪个键、要不要记账」。
4. 反方（新增 `BATTLE_FAILED_REVIVE`）唯一的吸引力是「让动作分支更直观」，但代价是每个
   `when (pageState)` 都要多一个分支，并且任何漏改的 `when` 默认落进 `else`（安全但静默）。

**动作：新增 `LabyrinthPostEntryActionKind.BATTLE_FAILURE_NEXT`**（`LabyrinthPostEntryActionKind.kt`
的 `// Battle` 段，紧随 `BATTLE_RETRY_SWITCH_MULTI`）。

理由：现行 `BATTLE_FAILED` 分支把 `retryButtonRect` 配给 `BATTLE_RETRY`，而
`dispatchPostEntryTap` 的提交副作用（`LabyrinthEntryRecognitionSession.kt:6001-6039`）会
`battleRetryCount++`、清空 `failedBattleTeamSignatures`/`committedBattleCharacterIds`、
重置 `combatContext.teamIndex=1`。点「下一步」不会发起重试，这些副作用全是错的
（重试预算被无谓消耗 → 提前触发「连续3次失败 reroll」/「达到重试上限」）。

三处最小改动：

```kotlin
// (1) :4732 前置策略块 —— 只对三键变体生效
if (pageState == LabyrinthEntryPageState.BATTLE_FAILED) {
    if (result.battleFailure?.layout == LabyrinthBattleFailureLayout.TWO_BUTTON) {
        // 下一步会把流程推进到遗物效果结果弹窗，不涉及任何重试预算/队伍回退策略。
        if (result.battleFailure == null) { finishFromPlanner(sessionId, "战斗失败页按钮结构未达到安全线；不执行固定坐标重试"); return }
    } else {
        … 原有 :4733-4803 内容原样保留 …
    }
}

// (2) :5281 计划分支
LabyrinthEntryPageState.BATTLE_FAILED ->
    result.battleFailure?.let { failure ->
        when (failure.layout) {
            LabyrinthBattleFailureLayout.TWO_BUTTON ->
                LabyrinthPostEntryTapPlan(
                    LabyrinthPostEntryActionKind.BATTLE_FAILURE_NEXT,
                    "战斗失败页(遗物复活)：下一步",
                    failure.retryButtonRect,          // 可点击的右下矩形（映射自 RETRY_BUTTON_RECT）
                )
            LabyrinthBattleFailureLayout.THREE_BUTTON ->
                … 原有 :5283-5295 内容原样保留（含 pendingSingleBossFallbackToMulti 分支）…
        }
    }

// (3) :5996 when (kind) 提交副作用（该 when 有 else -> Unit，所以不加分支也能编译，但语义要显式）
LabyrinthPostEntryActionKind.BATTLE_FAILURE_NEXT -> {
    battleWait.reset()
    postEntryStableFrames = 0
    postEntryAttempts = 0
}
```

**必须成立的三条**（均已用单测/实测确认）：

- **不得落入 UNKNOWN**：`detect()` 返回非 null ⇒ 处理器 `:508-516` 强制 `BATTLE_FAILED`。
  与页面状态无关，与分类器无关。
- **不得被判成会话失效（`labyrinthSessionBlockObservation == NONE`）**：实测该帧
  `SessionBlockKind=NONE`（用例 `two button capture fails the end gate and nothing else` 断言），
  且 `handleSessionBlockFrame` 在 `:1999` 是最外层，返回 `false` 后才会走到动作层。
  （`labyrinthSessionBlockObservation` 只看 anchorScores，不看页面状态，所以不会因为这次改动改变。）
- **必须给出可点击的右下矩形**：`retryButtonRect` 在两种布局下都是
  `ReferenceFitMapper.map(RETRY_BUTTON_RECT=(1405,935,430,110))`。1920x1080 = `(1405,935,430,110)`，
  中心 `(1620,990)`；2848x1320 = `(1968,1143,525,134)`，中心 `(2230,1210)`。
  两个中心都落在按钮实测范围内（1080p `[1412,945..1823,1033]`；截图
  `[1976,1153..2479,1259]`）。`handlePostEntryTap` 的 `:5398` 越界检查也通过。

**`SessionExpiryFrameTracker.kt` 是否需要动：不需要。**
`:35 battleFailureEndPoint = result.battleFailure?.endButtonRect?.let(::centre)` —— 可空类型下
安全调用自动给出 null；`:57 battleEndConfirmationPoint ?: battleFailureEndPoint.takeIf { pageState == BATTLE_FAILED }`
随之得到 null，`sessionInvalidationTrigger` 在该帧为 null，探针不会乱点。
语义上也正确：`:44-53` 的注释说明失败页靠「结束 → 撤退（无报酬）→ 确认」触发失效检测，
**两键页根本没有『结束』**，而且该页是过渡帧（下一步 → 遗物弹窗 → 地图），
不应在这里做失效探针。三键页行为完全不变。

### 3.3 后续链路：点右下 → 遗物效果结果 → 关闭 → 回地图

链路与帧的归属（实测数字来自 `relic-effect-result-20260922.png` 跑真实处理器）：

| 步 | 帧 | 页面状态 | 谁拥有这一帧 | 动作 |
|---|---|---|---|---|
| N | 两键失败页 | `BATTLE_FAILED`（layout=TWO_BUTTON） | `handlePostEntryPage` → `:5281` 分支 | `BATTLE_FAILURE_NEXT` @ `retryButtonRect` |
| N+1 | 遗物效果结果 | `ITEM_REWARD` | `handlePostEntryPage`：`genericConfirmOwnsFrame`(`:2302-2306`) → `labyrinthGenericConfirmDialogRect`(`:4918-4925`) | `CONFIRM_GENERIC_DIALOG` @ 关闭矩形 |
| N+2 | 地图 | `NODE_SELECTION` | 节点流程 | 正常路线执行 |

实测：`state=ITEM_REWARD`，`RELIC_EFFECT_RESULT_TITLE=1.0`，`RELIC_EFFECT_RESULT_CLOSE=1.0`，
`labyrinthGenericConfirmDialogRect(result) = EntryPixelRect(750,690,415,102)` —— **正好等于**
`RELIC_EFFECT_RESULT_CLOSE` 的命中矩形（`LabyrinthPageRuntimePolicy.kt:561-568` 的前置分支），
`labyrinthSessionBlockObservation = NONE`。

**为什么这一步不需要动 `LabyrinthEntryRecognitionSession.kt`：**

1. 拦截顺序上没有任何东西会先吃掉这一帧：
   - `handleSessionBlockFrame`（`:1999`）→ `labyrinthGenericConfirmDialogRect != null` ⇒
     `LabyrinthEntryRecognitionSession.kt:960-962` 直接返回 `NONE`；
   - `handleBattleWaitFrame`（`:2138`）→ `labyrinthBossSettlementNextButtonRect` 需要
     `pageState == UNKNOWN`（本帧是 `ITEM_REWARD`）⇒ null；`LabyrinthBattleWaitPolicy.observe`
     对 `ITEM_REWARD` 走 `else -> false` ⇒ `reset()` + `NONE`（而且（3）的提交分支已经
     `battleWait.reset()`）。
2. 关闭动作有两条既有实现，且都已命中：主路径是 `:4918-4925` 的 `CONFIRM_GENERIC_DIALOG`
   （实测命中）；兜底是 `:4942-4962` 的 `ITEM_REWARD` 分支（`RELIC_EFFECT_RESULT_CLOSE` →
   `ITEM_REWARD_CLOSE` → `MODAL_CLOSE`）。两条路都不依赖失败页的布局。
3. 因此本次改动**只需要 §3.2 的三处**（都是因为新增了动作种类），不需要为弹窗链路改一行。

写入本报告的交叉验证：`LabyrinthOpeningPopupFixtureTest`（2026-09-23 上游修复）已经断言
`labyrinthGenericConfirmDialogRect` 对这类弹窗返回关闭矩形、且不是会话失效；本次只是把它
接到两键失败页后面，链路本身没有新风险。

### 3.4 与 BOSS 结算 / `BATTLE_RESULT` 的优先级

判定顺序（结构上，不依赖分数高低）：

1. `LabyrinthEntryFrameProcessor.processPrepared`
   - `:500 detect(frame)`；`:508-516` **在分类器结果之上覆盖** `BATTLE_FAILED`。
     这是全代码库唯一的状态覆盖点，且在分类器之后执行 ⇒ 两键帧不可能被读成
     `BATTLE_RESULT`。实测该页 `BATTLE_RESULT_NEXT_BUTTON=0.177`、`stateScores[BATTLE_RESULT]=0.078`，
     连竞争都谈不上；但**保证来自结构，不来自这个分差**。
2. `LabyrinthEntryRecognitionSession.onFrame:1999` `handleSessionBlockFrame` → `NONE` → false。
3. `:2138 handleBattleWaitFrame`：
   - `labyrinthBossSettlementNextButtonRect(pageState=BATTLE_FAILED, …)` **必为 null**
     （`LabyrinthPageRuntimePolicy.kt:370` 要求 `pageState == UNKNOWN`，有用例覆盖）；
   - `LabyrinthBattleWaitPolicy.observe(BATTLE_FAILED, …)`：`BATTLE_FAILED` 不在 `waitingPage`
     集合里（`LabyrinthBattleWaitPolicy.kt:32-43`）⇒ `reset()` + `NONE` ⇒ 返回 false，不吞帧。
4. `handlePostEntryPage`：`:4918` 的 generic-confirm 分支不命中（该页没有会话弹窗/CONFIRM 锚点，
   实测 `labyrinthGenericConfirmDialogRect == null`）→ 落到 `:5281` 的 `BATTLE_FAILED` 分支 → §3.2 的计划。
   `:4964-4996` 的 UNKNOWN 分支（Boss 结算）**结构上不可达**（页面状态不是 UNKNOWN）。

**必须遵守的两条禁令**（否则会破坏 §3.4 的优先级）：

- **不要**通过放宽 `labyrinthBossSettlementNextButtonRect`（例如去掉 `pageState == UNKNOWN`
  或放宽到非 BOSS 上下文）来实现两键变体。那条路径是给
  `boss-win-summary-20260911.png` 这类「UNKNOWN + BOSS + 蓝色下一步」页用的（实测
  `title=0.054` 所以失败检测器不匹配）；一旦放宽，非 Boss 页面会被卷进 Boss 结算状态机。
- **不要**把两键变体实现成 UNKNOWN 分支里的兜底（例如「UNKNOWN 且右下有蓝色按钮 → 点一下」）。
  那样会重新引入 `labyrinthHasBattleControls` / 动画兜底 / 停滞看门狗之间的竞争，
  而且失去页面归属（§3.2 的第 3 条要求就满足不了）。

**关于「BOSS 战不会触发星辉吐息」这条领域前提**：本文的设计**不依赖**它 —— 两键分支只看像素，
不看 `combatContext.kind`；也正是因为不依赖，才更不能顺手放宽 Boss 结算路径，
否则就把一个只在普通/EX 发生的页面形态挂到了 Boss 状态机上。这条前提值得在后续实测中
顺带记录（失败页出现时 `combatContext.kind` 是什么），但即使它被推翻，本设计的改动面不变。

---

## 4. 不做什么

1. **不改任何节点识别阈值**，不改 `LabyrinthEntryPageClassifier` 的 `minScore=0.45` /
   `minMargin=0.08`，不改任何 anchor 模板或 `EntryAnchorId` 定义。
2. **不改失败检测器的任何既有阈值与 ROI**：`minEndLightCoverage` 仅改变语义（必需 → 布局），
   `minRetryBlueCoverage` / `minReportLightCoverage` / `minReportBlueCoverage` /
   `minTitleBlueCoverage` 与五个矩形常量逐字保留。
3. **不做通用裁切检测/水印检测/分辨率白名单**。非 16:9 的处理完全交给既有
   `ReferenceFitMapper`；本次实测证明它在 2848x1320 帧上零参数命中三个地标，
   不需要任何新机制。
4. **不把 `battle-failure-relic-revive-20261004.jpg`（2848x1320，他人截图）入库**，也不把它当锚点几何基准
   （用户 2026-10-04 补上的 1920x1080 真机图 `battle-failure-two-button-20261004.jpg` 另当别论：
   它已入库并作为主基准，见 §6.1）。
   本机已在 `.git/info/exclude`（**本机文件，不进入提交**）里显式排除这两份拷贝：
   `/android/app/src/test/resources/labyrinth/battle-failure-relic-revive-20261004.jpg` 与
   `/20261004.jpg`（两份 SHA256 相同：`1BE25A45…FC9024`，396941 字节）。
   这样 `git add -A` 也不会把它们带进提交；**仍请在提交前 `git status` 复核一次**。
5. **不为通过用例放宽任何阈值**。`LabyrinthBattleFailureDetectorTest` 与
   `LabyrinthOpeningPopupFixtureTest` 一个字都不改；新增用例只做**追加**断言。
6. **不把两键流程耦合到 BOSS 结算或 `BATTLE_RESULT`**；`BATTLE_RESULT` 的
   `battle.result.next_button`（`1452,946,388x92` / `1412,966,414x92` 两个 STANDARD 定义）
   与 `labyrinthBossSettlementNextButtonRect` 全部保持原样。

---

## 5. 待复核判据：逐条最终结论（2026-10-04 收口）

用户已于 2026-10-04 提供真正的 **1920x1080** 真机两键截图，并已入库：
`android/app/src/test/resources/labyrinth/battle-failure-two-button-20261004.jpg`
（可用 `-Dlabyrinth.battleFailureTwoButton=<路径>` 覆盖）。非 16:9 的 2848x1320 他人截图仍是本机专用，
用 `-Dlabyrinth.battleFailureNonSixteenByNine=<路径>` 覆盖，缺失即 `assumeTrue` 跳过。

当初留的 8 条复核项，逐条结论与实测数字如下（原始输出见 §6.1、§6.4）：

| # | 复核项 | 预期 | 结论 | 实测（1920x1080 真机图） |
|---|---|---|---|---|
| 1 | 左下槽位 pale 覆盖 `endLight` | `< 0.10`（预期 0.000） | **已定案** | **0.000** |
| 2 | 右下槽位 blue 覆盖 `retryBlue` | `≥ 0.60` | **已定案**（余量 0.334） | **0.934** |
| 3 | 右上 `伤害报告` pale `legacyReportLight` | `≥ 0.48`（两键变体守卫的前提） | **已定案**（余量 0.29，守卫保留） | **0.770** |
| 4 | `战斗失败` 标题 blue | `≥ 0.18`（承重闸门） | **已定案**（余量 0.169，不动） | **0.349** |
| 5 | 右下按钮矩形 | 落在 `RETRY_BUTTON_RECT=(1405,935,430,110)` 内，中心 `(1620,990)` 在按钮实测范围内 | **已定案** | 检测器给出 `(1405,935,430,110)`，与三键帧**逐位相同**；按钮实测范围 `[1412,945..1823,1034]`，中心 `(1620,990)` 在其内 |
| 6 | 页面其余 UI（阿尔法强化面板、伤害报告）位置 | 与参考帧同框（偏差 ≤8px） | **已定案（强于预期）** | 设备帧**就是** 16:9 参考系：`scale=1.0`、`offset=(0,0)`，`伤害报告` 按钮偏差 ≤1px、`战斗失败` 标题逐位相同 |
| 7 | 按下「下一步」后的下一帧 | `遗物效果结果`，`RELIC_EFFECT_RESULT_TITLE/CLOSE ≈ 1.0`，`labyrinthGenericConfirmDialogRect != null` | **已确认（按用户实机为准）** | 链路取自**应用自己的记录**（权威）：fv713 动作 `战斗失败页(遗物复活)：下一步` → fv715 页面 `ITEM_REWARD` + 动作 `确认提示弹窗`（即 generic-confirm 分支拿到弹窗自带的「关闭」矩形并点击）→ fv719 `关闭获得道具界面` → fv719 `NODE_SELECTION 0.895` 回地图。归档帧 `000083-f1314.jpg` 经肉眼核对**就是**「遗物效果结果／因「星辉吐息」的迷宫遗物效果，战斗的结果从败北变为了胜利。」+ 关闭；该弹窗在缩放副本上的两个锚点也命中（`title=0.7567`、`close=0.9068`）。**补充说明**：副本上页面归属回落 `UNKNOWN`、generic-confirm 前置分支不接管、`sessionBlock` 报 `RECONNECT_PROMPTED`，这些都是 960x540 缩放的产物（页面分 `min(title, close)=0.757`，低于 0.80 强模态线）——同一帧在 1920x1080 上应用判 `ITEM_REWARD 1.000`，且 full-res 下该分支确实接管、`SessionBlockKind == NONE`（既有 `LabyrinthEntryFrameProcessorTest` 的遗物效果结果用例钉住：title=1.0 / close=1.0 / `genericConfirm=(750,690,415,102)`） |
| 8 | 正式 fixture 入库后 | 语料库扫描「除两键截图外无新增命中」 | **已定案** | `corpus 55 fixtures: current=3 proposed=5`；新增命中恰好是两张两键截图（入库真机图 + 本机 2848x1320 图），已入库的 3 张三键页全部未被改判 |

> **不要为了迁就 960x540 的归档副本下调这四道闸门或会话阻塞阈值。** 0.80（强模态线）、0.85（按钮分）、
> 0.45（`SESSION_RETURN_TITLE` 触发）与 0.60 这一组数字是 2026-09-17 / 09-23 两次线上误判修出来的防线
> （把「返回标题」的坐标点进失败页自己的结束确认弹窗、遗物效果结果被读成会话失效）；
> 缩放副本的分数偏低是采样网格与 JPEG 重编码的产物，正确做法是**在 1920x1080 原图上判定**，
> 而不是把线放下去。

两键变体的**触发条件**（什么情况下客户端把右下按钮从「重新挑战」换成「下一步」、
且不画「结束」）目前仍然只有用户的领域描述（星辉吐息 整局一次、BOSS 战不触发），
没有「触发前」的帧序列证据。2026-10-04 的归档帧给出了触发**之后**的连续画面
（`000076` 只有标题与面板、`000077` 才出现「下一步」，见 §6.3），但触发瞬间的动画序列仍未采集。
这不影响本次设计（两种布局走同一个矩形、动作分开记账），但会影响将来是否需要更早地介入该页。

---

## 6. 真机验证：2026-10-04 17:20 日志包（带修复的构建）

用户从 App 调试面板导出的日志包 `limingjie-debug-20261004-172026/`（工作区根目录）是本次改动
两份真机证据之一（另一份是已入库的 1920x1080 真机截图，见 §6.1）。日志包**不入库**
（帧里含真实游戏画面、角色与账号信息，已在 `.git/info/exclude` 以 `limingjie-debug-*/` 排除），
结论以本节形式入库。

### 6.1 1920x1080 真机原图（入库主基准）

用户 2026-10-04 补上的两键失败页原图，是本仓库里**第一张也是唯一一张 16:9 真机两键 fixture**：

```
android/app/src/test/resources/labyrinth/battle-failure-two-button-20261004.jpg   1920x1080
```

跑 `LabyrinthBattleFailureLayoutAuditTest` / `LabyrinthBattleFailureTwoButtonLayoutTest`
（`--tests '*LabyrinthBattleFailure*'`）时它的实测输出（`system-out`）：

```
[two-button-layout] device capture battle-failure-two-button-20261004.jpg 1920x1080
[two-button-layout] device capture gates end=0.000 retry=0.934 reportLegacy=0.770 reportCurrent=0.000 reportOk=true title=0.349
[two-button-layout] device capture ranking: rawClassifier=UNKNOWN nextButton=0.19898949660105936 battleResult=0.0776516815973187
[battle-failure-audit] two-button capture battle-failure-two-button-20261004.jpg 1920x1080
[battle-failure-audit] two-button gates end=0.000 retry=0.934 reportLegacy=0.770 reportCurrent=0.000 reportOk=true title=0.349
[battle-failure-audit] two-button processor verdict=BATTLE_FAILED confidence=0.6841272788175443
[battle-failure-audit] two-button ranking: rawClassifier=UNKNOWN nextButton=0.19898949660105936 battleResult=0.0776516815973187 endConfirm=false
[battle-failure-audit] battle-failure-two-button-20261004.jpg end slot EntryPixelRect(left=950, top=905, width=380, height=175) pale=null blue=null
[battle-failure-audit] device capture 1920x1080 scale=1.0 offset=(0.0,0.0)
[battle-failure-audit] landmark lower-right button: reference=[1412,945..1823,1033] 412x89 capture=[1412,945..1823,1034] 412x90
[battle-failure-audit] landmark damage report button: reference=[1541,49..1867,101] 327x53 capture=[1541,49..1867,100] 327x52
[battle-failure-audit] landmark failure heading: reference=[724,39..1197,164] 474x126 capture=[724,39..1197,164] 474x126
[battle-failure-audit] device capture button/report width ratio reference=1.2599388379204892 capture=1.2599388379204892
```

（前三行由 `LabyrinthBattleFailureTwoButtonLayoutTest` 打印，其余由 `LabyrinthBattleFailureLayoutAuditTest` 打印；
两个套件的完整 stdout 见附录 A.2，重新生成的命令见 A.3。）

用例侧钉住的不变量（`LabyrinthBattleFailureTwoButtonLayoutTest.the committed device capture is the primary two button baseline`）：
四个闸门按 0.02 容差断言（JPEG 解码与采样格使第三位小数与机器有关）；
`layout=TWO_BUTTON`、`endButtonRect=null`、`retryButtonRect=(1405,935,430,110)`、
`confidence=0.6841272788175443`（1e-6）；点击中心 `(1620,990)` 落在蓝色按钮实测范围内；
`labyrinthGenericConfirmDialogRect == null`（失败页没有被读成普通确认弹窗）、
`labyrinthSessionBlockObservation == NONE`。

要点：`end=0.000`（左下槽位确实没有「结束」）、`retry=0.934`、`reportLegacy=0.770`、
`title=0.349`，四项都在安全侧；**原始分类器仍给 `UNKNOWN`**，所以这一帧的 `BATTLE_FAILED`
身份和 §1.1 说的一样，**只能由 `LabyrinthBattleFailureDetector` 的覆盖产生**。
`retryButtonRect=(1405,935,430,110)` 与 1920x1080 三键参考帧**逐位相同**，即 §5 表格第 5 项成立。

### 6.2 这次跑的是带修复的构建

- `state/history.ndjson` 里出现了动作标签 **`战斗失败页(遗物复活)：下一步`**
  （`frameVersion=713`、`timestamp=1791105384332`、`archivedFrame=000078-f1309.jpg`）。
  这个字符串在 1.0.15（`HEAD`）的整棵树里出现 **0 次**，只存在于本次工作区改动
  （`LabyrinthEntryRecognitionSession.kt` 的 `labyrinthBattleFailureTapPlan`，即 §3.2 的动作分流）；
  核对命令：`git grep -c '遗物复活' HEAD -- android/app/src` → 无输出（0 处）。
- `README.txt` 记录的 `应用版本: 1.0.15 (10015)` 与 `HEAD` 的 `versionCode=10015` 一致 ——
  版本号没变，所以识别依据只能是那句只存在于工作区的动作标签。

### 6.3 真机上的完整链路（全部取自 `state/history.ndjson`，设备采集 1920x1080）

| frameVersion | 设备时间 | 页面判定 | 置信度 | 动作标签 / 匹配特征 | 归档帧 |
|---|---|---|---|---|---|
| 712 | 17:16:23.7 | `BATTLE_FAILED` | 0.680 | 特征 `battle.failure.visual`（两键页第一次被识别） | `000077-f1308.jpg` |
| 713 | 17:16:24.3 | `BATTLE_FAILED` | 0.680 | **`战斗失败页(遗物复活)：下一步`** | `000078-f1309.jpg` |
| 713 | 17:16:24.9 | `UNKNOWN` | 0.000 | （点击生效，右下按钮变「加载中…」） | `000079-f1310.jpg` |
| 714 | 17:16:26.2 | `NODE_SELECTION` | 0.934 | | `000081-f1312.jpg` |
| 715 | 17:16:26.8 | `ITEM_REWARD` | 1.000 | 特征 `entry.relic_effect_result.close`、`entry.relic_effect_result.title` | `000082-f1313.jpg` |
| 715 | 17:16:27.4 | `ITEM_REWARD` | 1.000 | **`确认提示弹窗`** | `000083-f1314.jpg` |
| 719 | 17:16:31.7 | `ITEM_REWARD` | 1.000 | **`关闭获得道具界面`** | `000090-f1321.jpg` |
| 719 | 17:16:32.3 | `NODE_SELECTION` | 0.895 | （已回到地图） | `000091-f1322.jpg` |
| 722 | 17:16:37.2 | `CHARACTER_JOINED` | 1.000 | `关闭角色加入结果` | `000095-f1326.jpg` |
| 783 | 17:18:52.2 | `BATTLE_FAILED` | **0.7084198618490655** | 同一局的三键失败页（`layout=THREE_BUTTON`）对照 | `000217-f1448.jpg` |

结论：**两键失败页 → 下一步 → 遗物效果结果 → 关闭 → 回地图 → 继续路线**在真机上全程走通；
同一局里置信度 0.708 的三键失败页照旧按「重新挑战」处理，两种布局没有互相干扰。

顺带纠正一条页面结构上的说法（原先写成「失败后先出现阿尔法强化引导页，然后是战斗失败页」）：
归档帧 `000076-f1307.jpg` 已经是战斗失败页本身 —— 标题「战斗失败」、右上「伤害报告」、
中部「收集阿尔法碎片，推进阿尔法强化吧！」面板都在位，**只是按钮行还没画出来**；
下一帧 `000077-f1308.jpg` 才出现右下「下一步」（这一帧被判 `BATTLE_FAILED / TWO_BUTTON`）。
即**阿尔法强化是失败页里的一块面板，不是独立的一页**，它只比按钮行早约一帧淡入
（这也解释了 `000073`–`000076` 连续四帧判 `UNKNOWN`）。

### 6.4 归档帧重放（可复现）

`frames/` 是面板逐帧归档的 **960x540** 副本（设备实际采集是每条 history 都记着的
`frameWidth=1920 frameHeight=1080`）。把归档帧喂回**出厂管线**即可复现判定：

```powershell
gradle -p android :app:testDebugUnitTest '--rerun-tasks' '--tests' '*LabyrinthReportBundleReplayTest*' `
    '-Pkotlin.compiler.execution.strategy=in-process' '--console=plain' '--no-daemon'
```

用例 `LabyrinthReportBundleReplayTest`（本机存在日志包时才跑，否则 `assumeTrue` 跳过）
的实测输出（`system-out` 逐字，完整）。回放的帧集合不再手写：它由 history 的 `archivedFrame` 列推导 ——
「遗物复活」那一帧起 4 帧（`CHAIN_FRAMES_AFTER_ADVANCE = 4`）加归档末尾 6 帧（`TAIL_FRAMES = 6`），
共 11 帧，末尾会打印实际回放计数：

```
[relic-chain] fv=713 000078-f1309.jpg recorded=BATTLE_FAILED action=战斗失败页(遗物复活)：下一步 replayed=BATTLE_FAILED genericConfirm=- sessionBlock=NONE
[relic-chain] fv=715 000083-f1314.jpg recorded=ITEM_REWARD action=确认提示弹窗 replayed=UNKNOWN genericConfirm=- sessionBlock=RECONNECT_PROMPTED
[relic-chain] popup replayed=UNKNOWN itemRewardScore=0.7567152764921278 title=0.7567152764921278 close=0.9068034502987292 genericConfirm=null sessionBlock=RECONNECT_PROMPTED
[report-bundle] 000078-f1309.jpg 960x540 page=BATTLE_FAILED conf=0.682 layout=TWO_BUTTON retry=EntryPixelRect(left=703, top=468, width=215, height=55) end=- genericConfirm=- sessionBlock=NONE
[report-bundle] 000079-f1310.jpg 960x540 page=UNKNOWN conf=0.000 layout=- retry=- end=- genericConfirm=- sessionBlock=NONE
[report-bundle] 000080-f1311.jpg 960x540 page=UNKNOWN conf=0.000 layout=- retry=- end=- genericConfirm=- sessionBlock=NONE
[report-bundle] 000081-f1312.jpg 960x540 page=NODE_SELECTION conf=0.905 layout=- retry=- end=- genericConfirm=- sessionBlock=NONE
[report-bundle] 000082-f1313.jpg 960x540 page=UNKNOWN conf=0.000 layout=- retry=- end=- genericConfirm=- sessionBlock=RECONNECT_PROMPTED
[report-bundle] 000506-f92.jpg 960x540 page=UNKNOWN conf=0.000 layout=- retry=- end=- genericConfirm=- sessionBlock=NONE
[report-bundle] 000507-f93.jpg 960x540 page=UNKNOWN conf=0.000 layout=- retry=- end=- genericConfirm=- sessionBlock=NONE
[report-bundle] 000508-f94.jpg 960x540 page=UNKNOWN conf=0.000 layout=- retry=- end=- genericConfirm=- sessionBlock=NONE
[report-bundle] 000509-f95.jpg 960x540 page=UNKNOWN conf=0.000 layout=- retry=- end=- genericConfirm=- sessionBlock=NONE
[report-bundle] 000510-f96.jpg 960x540 page=UNKNOWN conf=0.000 layout=- retry=- end=- genericConfirm=- sessionBlock=NONE
[report-bundle] 000511-f97.jpg 960x540 page=UNKNOWN conf=0.000 layout=- retry=- end=- genericConfirm=- sessionBlock=NONE
[report-bundle] replayed 11 of 11 recorded frames
```

重放里的两键帧（`000078-f1309.jpg`）给出点击框 `(703,468,215x55)`，在 1080p 参考系下就是
`(1405,935,430,110)`（= §2.1 的 `RETRY_BUTTON_RECT`），与设备原帧上的检测器输出**逐位相同** ——
即 §5 表格第 5 项成立。三键帧的 `end` 矩形不在这个重放集合里（选择器只取遗物复活链与归档末尾），
它由 §6.1 的 1920x1080 真机图和 `LabyrinthBattleFailureDetectorTest` 覆盖。

**边界（必须和上面的结论一起读）**：

- 这一段（归档帧重放 + 历史记录）来自**带修复的构建**（§6.2）与**面板 960x540 归档副本**，
  不是干净检出上的官方 APK；归档副本是缩放图，判定数字与设备原帧有小数级差异（见下一条）；
- `NODE_SELECTION` 在归档副本上是 0.905、设备原帧上是 0.934（缩放差异），两者一致地通过分类门槛；
- §5 表格第 1–4、8 项的**定案依据是已入库的 1920x1080 原图**（§6.1），不是这些缩放副本。

---

## 附录 A：本次实测原始输出

> **两段输出分属两个代码状态，不要混读**：A.1 是**修复前（1.0.15，`end` 仍是必需闸门时）**的记录，
> 用来说明「两键帧为什么会整帧失去页面归属」；A.2 是**当前工作区**的真实输出。
> A.2 各块逐字取自 2026-10-04 的一次复跑；**测试或打印文案改动后必须重新生成**（命令见 A.3）。

### A.1 修复前（1.0.15）

两键截图当时只差 `end` 一道闸门：其余三道必需条件全过，`detect()` 仍然返回 null，
于是整帧丢掉 `BATTLE_FAILED` 身份、动作被禁用（与今天的修复对照着看）：

```
[battle-failure-audit] reference verdict=BATTLE_FAILED confidence=0.7080125258222605
[battle-failure-audit] reference end=EntryPixelRect(left=945, top=935, width=430, height=110)
                        retry=EntryPixelRect(left=1405, top=935, width=430, height=110)
[battle-failure-audit] raw-classifier verdict=UNKNOWN confidence=0.0 (no BATTLE_FAILED key)
[battle-failure-audit] reference gates end=0.895 retry=0.823 reportLegacy=0.771 reportCurrent=0.000 reportOk=true title=0.343
[battle-failure-audit] two-button capture battle-failure-relic-revive-20261004.jpg 2848x1320
[battle-failure-audit] two-button gates end=0.000 retry=0.912 reportLegacy=0.779 reportCurrent=0.001 reportOk=true title=0.341
[battle-failure-audit] two-button processor verdict=UNKNOWN battleFailure=null
[battle-failure-audit] two-button ranking: rawClassifier=UNKNOWN nextButton=0.188 battleResult=0.080 endConfirm=false
[battle-failure-audit] capture retry sample EntryPixelRect(left=2060, top=1179, width=317, height=68) retryBlue=0.912
[battle-failure-audit] capture scale=1.2222222222222223 predictedOffsetX=250.667
[battle-failure-audit] landmark failure heading:    normalised=[724,39..1195,160]   impliedOffsetX=251.11
[battle-failure-audit] corpus 54 fixtures: current=3 proposed=4
[battle-failure-audit] corpus matches:
  battle_failure_20260910.jpg 1920x1080 end=0.895 retry=0.823 report=0.771 title=0.343 layout=THREE_BUTTON
  ex_battle_failure_20260917.png 1178x663 end=0.886 retry=0.844 report=0.731 title=0.358 layout=THREE_BUTTON
  ex_monster_detail_live_20260910.jpg 1920x1080 (SHA256 同 battle_failure_20260910.jpg) layout=THREE_BUTTON
  battle-failure-relic-revive-20261004.jpg 2848x1320 end=0.000 retry=0.912 report=0.779 title=0.341 layout=TWO_BUTTON
[battle-failure-audit] relic-effect-result state=ITEM_REWARD title=1.0 close=1.0
                        genericConfirm=EntryPixelRect(left=750, top=690, width=415, height=102)
```

（这一代的几何打印用词是 `pill`，后来随术语统一改成 `button`，上面只保留与本次结论直接相关的行，
避免两种用词混读；当时语料库是 54 个图片文件、提议规则命中 4 个。）

### A.2 修复后（当前工作区，逐字）

`LabyrinthBattleFailureLayoutAuditTest`（12/12）与 `LabyrinthBattleFailureTwoButtonLayoutTest`（8/8）
的完整 stdout（先 audit，后 two-button-layout）：

```
[battle-failure-audit] two-button capture battle-failure-two-button-20261004.jpg 1920x1080
[battle-failure-audit] two-button gates end=0.000 retry=0.934 reportLegacy=0.770 reportCurrent=0.000 reportOk=true title=0.349
[battle-failure-audit] two-button processor verdict=BATTLE_FAILED confidence=0.6841272788175443
[battle-failure-audit] two-button ranking: rawClassifier=UNKNOWN nextButton=0.19898949660105936 battleResult=0.0776516815973187 endConfirm=false
[battle-failure-audit] corpus 55 fixtures: current=3 proposed=5 production=5
[battle-failure-audit] corpus matches:
[battle-failure-audit] corpus two-button frames: [battle-failure-relic-revive-20261004.jpg (2848x1320), battle-failure-two-button-20261004.jpg (1920x1080)]
[battle-failure-audit] reference blue button [1412,945..1823,1033] 412x89
[battle-failure-audit] capture blue button [1976,1153..2479,1259] 504x107 normalised [1412,943..1823,1030] 412x88
[battle-failure-audit] capture retry sample EntryPixelRect(left=2060, top=1179, width=317, height=68) retryBlue=0.9117647058823529
[battle-failure-audit] failure page ranking: state=BATTLE_FAILED nextButton=0.17655255330472414 battleResult=0.07820438780132516
[battle-failure-audit] synthetic two-button gates end=0.000 retry=0.823 reportLegacy=0.771 reportCurrent=0.000 reportOk=true title=0.343
[battle-failure-audit] synthetic two-button layout=TWO_BUTTON confidence=0.645848202927849 retry=EntryPixelRect(left=1405, top=935, width=430, height=110)
[battle-failure-audit] relic-effect-result state=ITEM_REWARD title=1.0 close=1.0 genericConfirm=EntryPixelRect(left=750, top=690, width=415, height=102)
[battle-failure-audit] reference gates end=0.895 retry=0.823 reportLegacy=0.771 reportCurrent=0.000 reportOk=true title=0.343
[battle-failure-audit] 1080p pale "结束" button columns=959..1351 rows=946..1022, endLight=0.8945054945054945
[battle-failure-audit] battle-failure-two-button-20261004.jpg end slot EntryPixelRect(left=950, top=905, width=380, height=175) pale=null blue=null
[battle-failure-audit] battle-failure-relic-revive-20261004.jpg end slot EntryPixelRect(left=1425, top=1119, width=438, height=188) pale=[1445,1121..1541,1165] 97x45 blue=null
[battle-failure-audit] raw-classifier verdict=UNKNOWN confidence=0.0 (no BATTLE_FAILED key)
[battle-failure-audit] processor verdict=BATTLE_FAILED via battle.failure.visual
[battle-failure-audit] capture scale=1.2222222222222223 predictedOffsetX=250.66666666666652
[battle-failure-audit] landmark lower-right button: ref=[1412,945..1823,1033] 412x89 capture=[1976,1153..2479,1259] 504x107 normalised=[1412,943..1823,1030] 412x88 impliedOffsetX=250.22222222222217
[battle-failure-audit] landmark damage report button: ref=[1541,49..1867,101] 327x53 capture=[2134,60..2532,123] 399x64 normalised=[1541,49..1867,101] 327x53 impliedOffsetX=250.55555555555543
[battle-failure-audit] landmark failure heading: ref=[724,39..1197,164] 474x126 capture=[1136,48..1711,195] 576x148 normalised=[724,39..1195,160] 472x122 impliedOffsetX=251.1111111111111
[battle-failure-audit] button/report width ratio reference=1.2599388379204892 capture=1.263157894736842 (delta=0.002554931016862551)
[battle-failure-audit] reference verdict=BATTLE_FAILED confidence=0.7080125258222605
[battle-failure-audit] reference end=EntryPixelRect(left=945, top=935, width=430, height=110) retry=EntryPixelRect(left=1405, top=935, width=430, height=110)
[battle-failure-audit] device capture 1920x1080 scale=1.0 offset=(0.0,0.0)
[battle-failure-audit] landmark lower-right button: reference=[1412,945..1823,1033] 412x89 capture=[1412,945..1823,1034] 412x90
[battle-failure-audit] landmark damage report button: reference=[1541,49..1867,101] 327x53 capture=[1541,49..1867,100] 327x52
[battle-failure-audit] landmark failure heading: reference=[724,39..1197,164] 474x126 capture=[724,39..1197,164] 474x126
[battle-failure-audit] device capture button/report width ratio reference=1.2599388379204892 capture=1.2599388379204892
```

```
[two-button-layout] device capture battle-failure-two-button-20261004.jpg 1920x1080
[two-button-layout] device capture gates end=0.000 retry=0.934 reportLegacy=0.770 reportCurrent=0.000 reportOk=true title=0.349
[two-button-layout] device capture ranking: rawClassifier=UNKNOWN nextButton=0.19898949660105936 battleResult=0.0776516815973187
[two-button-layout] unchanged failure fixture battle_failure_20260910.jpg 1920x1080 end=0.8945054945054945 retry=0.823076923076923
[two-button-layout] unchanged failure fixture ex_battle_failure_20260917.png 1178x663 end=0.8861111111111111 retry=0.8444444444444444
[two-button-layout] unchanged failure fixture ex_monster_detail_live_20260910.jpg 1920x1080 end=0.8945054945054945 retry=0.823076923076923
```

### A.3 重新生成

```powershell
pwsh -File .toolchain/work/t11-run-audit-suites.ps1     # 本机脚本（不入库）：--rerun-tasks 后导出 XML 的 system-out
```

手工等价命令：

```powershell
$root='D:\limingjie-assistant'
$env:GRADLE_USER_HOME="$root\.gradle-local"; $env:ANDROID_HOME="$root\.android-sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME; $env:ANDROID_USER_HOME="$root\.android-user"
$env:JAVA_HOME='D:\JAVA\jdk-17.0.1'; Remove-Item Env:\ANDROID_SDK_HOME -ErrorAction SilentlyContinue
& "$root\.toolchain\gradle-8.7\bin\gradle.bat" -p "$root\android" :app:testDebugUnitTest '--rerun-tasks' `
    '--tests' '*LabyrinthBattleFailureLayoutAuditTest*' `
    '--tests' '*LabyrinthBattleFailureTwoButtonLayoutTest*' `
    '--tests' '*LabyrinthReportBundleReplayTest*' `
    '-Pkotlin.compiler.execution.strategy=in-process' '--console=plain' '--no-daemon'
```

**`--rerun-tasks` 是必需的**：这三个套件会读取工作区里的真实截图（`test/resources` 下的两键图、
本机 2848x1320 图），而 Gradle 只跟踪任务声明的输入 —— 图片被替换或移走时任务可能仍报 UP-TO-DATE，
把上一次的 stdout 当成本次结果（本仓库实测踩过）。stdout 落在
`android/app/build/test-results/testDebugUnitTest/TEST-*.xml` 的 `<system-out>` 里。

---

## 附录 B：改动清单

| 文件 | 改动 | 参考行 |
|---|---|---|
| `vision/LabyrinthBattleFailureRecognition.kt` | 新增 `LabyrinthBattleFailureLayout`；`LabyrinthBattleFailureObservation` 增 `layout`、`endButtonRect` 变可空；`detect()` 三道必需闸门 + 两个返回分支（含「两键变体要求右上『伤害报告』同时在线」的守卫） | 5-9, 44-56 |
| `LabyrinthPostEntryActionKind.kt` | 新增 `BATTLE_FAILURE_NEXT` | `// Battle` 段（约 45-50） |
| `LabyrinthEntryRecognitionSession.kt` | (1) `:4732` 前置策略块按 layout 分流；(2) `:5281` 计划分支；(3) `:5996` 提交副作用分支 | 3 处 |
| `test/.../LabyrinthBattleFailureLayoutAuditTest.kt` | 两键/三键布局与语料的审计用例（当前 12 条）：断言检测器给出的 `layout`、`endButtonRect`、`retryButtonRect`，并把「两键变体要求右上『伤害报告』同时在线」的守卫用 `legacyReportLight` 单独钉住；与 §3.1 的约定逐条对应 | 新文件 |

未改动且必须保持不改：`LabyrinthEntryClassifier.kt`、`LabyrinthBattleWaitPolicy.kt`、
`LabyrinthPageRuntimePolicy.kt`、`SessionExpiryFrameTracker.kt`、
`LabyrinthBattleFailureDetectorTest.kt`、`LabyrinthOpeningPopupFixtureTest.kt`。

**补齐说明（2026-10-04）**：另新增 `LabyrinthBattleFailureAdvanceTest`（会话层「推进 vs 重试」
分流，5 条）与 `LabyrinthReportBundleReplayTest`（§6.4 的真机归档帧重放，日志包不存在时自动跳过）；
`LabyrinthBattleFailureTwoButtonLayoutTest` 在本次收口里把 1920x1080 真机图定为主基准，
并新增「设备帧落在参考画布上」与语料库排除两键截图的用例。
真机证据有两类：入库的 1920x1080 真机截图（`battle-failure-two-button-20261004.jpg`，§6.1）
与**不入库**的调试日志包（§6.3/§6.4 的归档帧与 `state/history.ndjson`）。
