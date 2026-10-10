# 调试日志包诊断字段

下载诊断面板的日志 ZIP 即可获取以下信息，无需额外开启逐帧截图归档。

| 文件 | 内容 |
| --- | --- |
| `environment.json` | 应用版本、targetSdk、Android 版本/API/补丁、设备与系统构建、ABI、系统运行时长、显示器 ID/尺寸/旋转/刷新率/DPI、字体缩放与语言、无障碍设置开关及实际连接状态、导出时的进程与捕获状态 |
| `runtime/history.ndjson` | 独立后台线程每 10 秒采样，最多 360 条：PID 与进程启动时间、进程 CPU、Java/native 堆、PSS、安卓可用内存、前台包及捕获状态；识别暂停或结束后继续采样 |
| `state/latest.json`、`state/history.ndjson` | 当前帧 OCR 缓存命中原因、缓存年龄及寿命、图像指纹距离、裁剪区域、OCR 请求与耗时、事件匹配分数/稳定帧、各选项的按钮位置与可用分数、语义决策及限制原因 |
| `logs/logcat.txt` | OCR 请求/回调、语义决策清空、重试与兜底来源、无障碍连接/解绑/销毁、手势 ID/主线程排队/回调耗时、投影启动/内容尺寸和可见性变化/停止回调 |

环境信息是导出时的状态，不能代替故障时的状态历史。每条运行采样包含 `timestamp` 与 `elapsedRealtimeMillis`，与识别状态的同名字段对齐。跨进程比较内存时同时核对 PID 和 `processStartElapsedRealtimeMillis`。

`processCpuPercentOfOneCore` 以一个 CPU 核为 100%，多线程时可以超过 100%；它仅表示助手的安卓进程，不表示电脑、模拟器宿主进程或游戏进程的 CPU 使用率。内存字段以 `Bytes` 或 `KiB` 明确单位。模拟器上 `reportedXdpi/Ydpi` 是系统报告值，不代表物理显示器像素密度。

`eventOcr.cache.status` 分为 `NO_CACHED_TEXT`、`CLOCK_ROLLBACK`、`EXPIRED`、`CROP_CHANGED`、`FINGERPRINT_CHANGED`、`HIT`。`lastResultStatus` 区分返回文字、空文字和失败/不可用；请求尚未完成时可查看 `pendingRequestId` 与 `pendingAgeMillis`。OCR 原文仅来自事件裁剪区域，最多保存 2,000 字符。

这次只增加诊断，未改变 5 秒 OCR 缓存寿命、6 秒点击重试间隔、事件选项策略或屏幕捕获行为。日志能帮助确认故障分支；此前发现的多选事件误用单选兜底仍需另行修复。

Android 的 `MediaProjection.Callback.onStop()` 没有提供具体停止原因；日志会记录回调发生，但不会据此断言用户停止、锁屏或其他录屏抢占。当前系统选择器返回的数据也没有可靠区分“单个应用/整个屏幕”的公开字段，因此不猜测捕获授权范围。电脑端 CPU/RAM/GPU 负载需通过宿主机另行采集。
