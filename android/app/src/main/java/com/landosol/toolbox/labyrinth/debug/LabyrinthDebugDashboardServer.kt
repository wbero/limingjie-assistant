package com.landosol.toolbox.labyrinth.debug

import android.graphics.Bitmap
import android.util.Log
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.labyrinth.LabyrinthEntryRecognitionSessionState
import com.landosol.toolbox.labyrinth.LabyrinthEventChoiceDecision
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny loopback-only HTTP dashboard for live labyrinth debugging.
 *
 * It intentionally has no third-party server dependency. A browser inside the emulator can open
 * http://127.0.0.1:8765/ directly. For a browser on the host PC, expose it with
 * `adb forward tcp:8765 tcp:8765` first.
 */
class LabyrinthDebugDashboardServer(
    private val port: Int = DEFAULT_PORT,
    /** Directory for the optional per-frame archive; null disables the feature entirely. */
    private val frameArchiveDirectory: File? = null,
    private val runtimeDiagnostics: LabyrinthRuntimeDiagnostics? = null,
) {
    private data class Snapshot(
        val json: ByteArray,
        val jpeg: ByteArray?,
        val frameVersion: Long,
        /** Capture time of [jpeg], which is not always the time of [json] — see [publish]. */
        val jpegMillis: Long = Long.MIN_VALUE,
    )

    private val running = AtomicBoolean(false)
    private val historyLock = Any()
    /** Per-frame JPEG archive for offline replay. Off by default; toggled from the dashboard. */
    private val frameArchiveEnabled = AtomicBoolean(false)
    private val frameArchiveLock = Any()
    private var frameArchiveSequence = 0L
    private val frameArchiveFiles = ArrayDeque<File>()
    private val recentStates = ArrayDeque<ByteArray>()
    private val latest = AtomicReference(
        Snapshot(
            json = "{\"status\":\"waiting\"}".toByteArray(Charsets.UTF_8),
            jpeg = null,
            frameVersion = 0L,
        ),
    )
    /** Capture time of the JPEG currently stored in [latest]. */
    @Volatile
    private var latestJpegMillis = Long.MIN_VALUE
    @Volatile
    private var lastJpegAt = Long.MIN_VALUE

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val server = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                // Bind synchronously so the dashboard is already reachable before the UI can
                // launch the external browser.
                bind(InetSocketAddress(InetAddress.getByName(IPV4_LOOPBACK), port))
            }
        }.getOrElse { failure ->
            running.set(false)
            Log.e(LOG_TAG, "debug dashboard failed to bind $IPV4_LOOPBACK:$port", failure)
            return
        }
        Log.i(LOG_TAG, "debug dashboard listening on http://$IPV4_LOOPBACK:$port/")
        runtimeDiagnostics?.start()
        Thread({ runServer(server) }, "labyrinth-debug-dashboard").apply {
            isDaemon = true
            start()
        }
    }

    fun publish(
        bitmap: Bitmap,
        state: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult,
        presentation: AutomationOverlayPresentation,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val previous = latest.get()
        val shouldRefreshJpeg = previous.jpeg == null ||
            lastJpegAt == Long.MIN_VALUE ||
            nowMillis - lastJpegAt >= JPEG_REFRESH_INTERVAL_MILLIS
        val jpeg = if (shouldRefreshJpeg) {
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                output.toByteArray()
            }.also { lastJpegAt = nowMillis }
        } else {
            previous.jpeg
        }
        val version = if (shouldRefreshJpeg) previous.frameVersion + 1L else previous.frameVersion
        val jpegMillis = if (shouldRefreshJpeg) nowMillis else previous.jpegMillis
        latestJpegMillis = jpegMillis
        val archivedFrame = archiveFrame(bitmap, state.frameCount)
        val json = buildJson(state, result, presentation, version, nowMillis, archivedFrame, jpegMillis)
            .toString()
            .toByteArray(Charsets.UTF_8)
        latest.set(Snapshot(json = json, jpeg = jpeg, frameVersion = version, jpegMillis = jpegMillis))
        synchronized(historyLock) {
            recentStates.addLast(json)
            while (recentStates.size > MAX_STATE_HISTORY_ENTRIES) recentStates.removeFirst()
        }
    }

    /**
     * Write one downscaled JPEG per recognized frame while the archive switch is on. The archive
     * is bounded to the same depth as the state history so the diagnostic zip stays around a few
     * tens of megabytes. Returns the archive file name recorded in the history entry, or null.
     */
    private fun archiveFrame(bitmap: Bitmap, frameCount: Long): String? {
        if (!frameArchiveEnabled.get()) return null
        val directory = frameArchiveDirectory ?: return null
        return runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) return null
            val scale = FRAME_ARCHIVE_WIDTH.toFloat() / bitmap.width
            val scaled = if (bitmap.width > FRAME_ARCHIVE_WIDTH) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    FRAME_ARCHIVE_WIDTH,
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val name: String
            val file: File
            synchronized(frameArchiveLock) {
                frameArchiveSequence++
                name = "%06d-f%d.jpg".format(Locale.US, frameArchiveSequence, frameCount)
                file = File(directory, name)
                frameArchiveFiles.addLast(file)
                while (frameArchiveFiles.size > MAX_STATE_HISTORY_ENTRIES) {
                    frameArchiveFiles.removeFirst().delete()
                }
            }
            file.outputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, FRAME_ARCHIVE_JPEG_QUALITY, output)
            }
            if (scaled !== bitmap) scaled.recycle()
            name
        }.getOrElse { failure ->
            Log.w(LOG_TAG, "frame archive write failed", failure)
            null
        }
    }

    private fun setFrameArchiveEnabled(enabled: Boolean) {
        frameArchiveEnabled.set(enabled)
        if (!enabled) return
        synchronized(frameArchiveLock) {
            // A fresh archive per enable so a replay bundle never mixes two sessions.
            frameArchiveFiles.forEach(File::delete)
            frameArchiveFiles.clear()
            frameArchiveSequence = 0L
        }
    }

    private fun archivedFramesSnapshot(): List<File> = synchronized(frameArchiveLock) {
        frameArchiveFiles.filter(File::isFile)
    }

    private fun runServer(server: ServerSocket) {
        runCatching {
            server.use {
                while (running.get()) {
                    val socket = server.accept()
                    Thread({ handle(socket) }, "labyrinth-debug-http").apply {
                        isDaemon = true
                        start()
                    }
                }
            }
        }.onFailure { failure ->
            Log.e(LOG_TAG, "debug dashboard stopped unexpectedly on $IPV4_LOOPBACK:$port", failure)
        }
        running.set(false)
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = SOCKET_TIMEOUT_MILLIS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val requestLine = readLine(input) ?: return
            // Consume the small request header so browsers can reuse their normal request path.
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
            when (path) {
                "/", "/index.html" -> respond(output, "text/html; charset=utf-8", DASHBOARD_HTML)
                "/api/state" -> respond(output, "application/json; charset=utf-8", latest.get().json)
                "/api/archive/on" -> {
                    setFrameArchiveEnabled(frameArchiveDirectory != null)
                    respond(output, "application/json; charset=utf-8", archiveStatusJson())
                }
                "/api/archive/off" -> {
                    setFrameArchiveEnabled(false)
                    respond(output, "application/json; charset=utf-8", archiveStatusJson())
                }
                "/api/archive" -> respond(output, "application/json; charset=utf-8", archiveStatusJson())
                "/logs.zip", "/download/logs.zip" -> {
                    val snapshot = latest.get()
                    val history = synchronized(historyLock) { recentStates.toList() }
                    val zip = buildDiagnosticZip(snapshot, history, collectOwnProcessLogcat(), archivedFramesSnapshot())
                    val filename = "limingjie-debug-${timestampForFilename()}.zip"
                    respond(
                        output = output,
                        contentType = "application/zip",
                        body = zip,
                        extraHeaders = mapOf("Content-Disposition" to "attachment; filename=\"$filename\""),
                    )
                }
                "/frame.jpg" -> {
                    val jpeg = latest.get().jpeg
                    if (jpeg == null) {
                        respond(output, "text/plain; charset=utf-8", "frame not ready", status = "404 Not Found")
                    } else {
                        respond(output, "image/jpeg", jpeg)
                    }
                }
                else -> respond(output, "text/plain; charset=utf-8", "not found", status = "404 Not Found")
            }
        }
    }

    private fun respond(
        output: BufferedOutputStream,
        contentType: String,
        body: String,
        status: String = "200 OK",
    ) = respond(output, contentType, body.toByteArray(Charsets.UTF_8), status)

    private fun respond(
        output: BufferedOutputStream,
        contentType: String,
        body: ByteArray,
        status: String = "200 OK",
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val header = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        output.write(header)
        output.write(body)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < MAX_HEADER_LINE_BYTES) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.UTF_8.name())
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes.write(next)
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun archiveStatusJson(): String = JSONObject().apply {
        put("supported", frameArchiveDirectory != null)
        put("enabled", frameArchiveEnabled.get())
        put("frames", archivedFramesSnapshot().size)
    }.toString()

    private fun buildDiagnosticZip(
        snapshot: Snapshot,
        history: List<ByteArray>,
        logcat: ByteArray,
        archivedFrames: List<File> = emptyList(),
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }

            entry(
                "README.txt",
                buildString {
                    appendLine("黎明界助手调试日志包")
                    appendLine("应用版本: ${AppVersion.label}")
                    appendLine("生成时间: ${System.currentTimeMillis()}")
                    appendLine("服务地址: http://$IPV4_LOOPBACK:$port/")
                    appendLine("environment.json: 导出时的 Android 版本、设备、显示器、DPI、权限与运行状态")
                    appendLine("runtime/history.ndjson: 每 10 秒后台采样的进程 CPU/内存与捕获状态，最多保留约 1 小时；不包含电脑端负载")
                    appendLine("state/latest.json: 下载瞬间的结构化识别状态")
                    appendLine("state/history.ndjson: 最近 ${history.size} 条结构化状态历史")
                    appendLine(
                        "frame/latest.jpg: 最近一帧截图（若已有）；对应 frameVersion=" +
                            "${snapshot.frameVersion}，拍摄于 ${snapshot.jpegMillis}。" +
                            "截图每 $JPEG_REFRESH_INTERVAL_MILLIS ms 才刷新一次，因此它可能早于 " +
                            "state/latest.json：先比对两者的 frameVersion 再下结论",
                    )
                    appendLine("frames/: 逐帧缩放截图（仅在面板开启逐帧归档后存在，${archivedFrames.size} 张）")
                    appendLine("state/history.ndjson 每条的 archivedFrame 字段指向 frames/ 中的文件名")
                    appendLine("logs/logcat.txt: 当前 App 进程最近日志")
                }.toByteArray(Charsets.UTF_8),
            )
            entry("state/latest.json", snapshot.json)
            runtimeDiagnostics?.let { diagnostics ->
                entry("environment.json", diagnostics.environmentJson())
                entry("runtime/history.ndjson", diagnostics.historyNdjson())
            }
            entry(
                "state/history.ndjson",
                history.fold(ByteArrayOutputStream()) { output, json ->
                    output.apply {
                        write(json)
                        write('\n'.code)
                    }
                }.toByteArray(),
            )
            snapshot.jpeg?.let { entry("frame/latest.jpg", it) }
            archivedFrames.forEach { file ->
                runCatching { entry("frames/${file.name}", file.readBytes()) }
            }
            // The captured window holds only the most recent lines, so a long run can scroll the
            // startup banner out of it. Restate the build here rather than hope it survived.
            entry(
                "logs/logcat.txt",
                "# 黎明界助手 ${AppVersion.label}\n".toByteArray(Charsets.UTF_8) + logcat,
            )
        }
        bytes.toByteArray()
    }

    private fun collectOwnProcessLogcat(): ByteArray = runCatching {
        val process = ProcessBuilder(
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "--pid=${android.os.Process.myPid()}",
            "-t",
            LOGCAT_LINE_LIMIT.toString(),
        ).redirectErrorStream(true).start()
        process.inputStream.use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (total < MAX_LOGCAT_BYTES) {
                    val read = input.read(buffer, 0, minOf(buffer.size, MAX_LOGCAT_BYTES - total))
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                output.toByteArray()
            }
        }.also { process.destroy() }
    }.getOrElse { failure ->
        "logcat unavailable: ${failure.message ?: failure::class.java.simpleName}\n"
            .toByteArray(Charsets.UTF_8)
    }

    private fun timestampForFilename(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun buildJson(
        state: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult,
        presentation: AutomationOverlayPresentation,
        frameVersion: Long,
        nowMillis: Long,
        archivedFrame: String? = null,
        jpegMillis: Long = Long.MIN_VALUE,
    ): JSONObject = JSONObject().apply {
        put("appVersion", AppVersion.name)
        put("appVersionCode", AppVersion.code)
        put("timestamp", nowMillis)
        put("elapsedRealtimeMillis", android.os.SystemClock.elapsedRealtime())
        put("frameVersion", frameVersion)
        // The stored JPEG is refreshed at most every JPEG_REFRESH_INTERVAL_MILLIS, so several
        // consecutive rows share one picture and only the first of them was actually taken from
        // it. Say when the picture was taken, otherwise a reader pairs these scores with an image
        // of a different page and draws the wrong conclusion (2026-09-20 bundle 121348: five rows
        // shared frameVersion 6 while the page went UNKNOWN -> HOME -> UNKNOWN -> HOME).
        put("frameImageTimestamp", jpegMillis.takeIf { it != Long.MIN_VALUE } ?: JSONObject.NULL)
        put("frameImageStale", jpegMillis != Long.MIN_VALUE && jpegMillis != nowMillis)
        put("archivedFrame", archivedFrame ?: JSONObject.NULL)
        put("title", presentation.title)
        put("status", presentation.status)
        put("detail", presentation.detail.orEmpty())
        put("dryRun", presentation.dryRun)
        put("paused", presentation.paused)
        put("page", result.observation.state.name)
        put("pageConfidence", result.observation.confidence)
        put("pageReason", result.observation.reason ?: JSONObject.NULL)
        fun rectJson(rect: EntryPixelRect?): Any = rect?.let {
            JSONObject().apply {
                put("left", it.left)
                put("top", it.top)
                put("width", it.width)
                put("height", it.height)
            }
        } ?: JSONObject.NULL
        put("eventOcr", result.eventOcrDiagnostics?.let { diagnostic ->
            JSONObject().apply {
                put("status", diagnostic.status)
                put("crop", rectJson(diagnostic.cropRect))
                put("fingerprint", diagnostic.fingerprint ?: JSONObject.NULL)
                put("completeLayoutCount", diagnostic.completeLayoutCount ?: JSONObject.NULL)
                put("buttonConfidencesByOptionCount", JSONObject().apply {
                    diagnostic.buttonConfidencesByOptionCount.forEach { (count, scores) ->
                        put(count.toString(), JSONArray(scores))
                    }
                })
                put("requestScheduled", diagnostic.requestScheduled)
                put("rawText", diagnostic.rawText ?: JSONObject.NULL)
                put("candidateEventId", diagnostic.candidateEventId ?: JSONObject.NULL)
                put("candidateScore", diagnostic.candidateScore ?: JSONObject.NULL)
                put("rivalMargin", diagnostic.rivalMargin ?: JSONObject.NULL)
                put("stableFrames", diagnostic.stableFrames)
                put("trusted", diagnostic.trusted)
                put("cache", diagnostic.cache?.let { cache -> JSONObject().apply {
                    put("status", cache.status)
                    put("ageMillis", cache.ageMillis ?: JSONObject.NULL)
                    put("lifetimeMillis", cache.cacheLifetimeMillis)
                    put("fingerprintDistance", cache.fingerprintDistance ?: JSONObject.NULL)
                    put("cachedCrop", rectJson(cache.cachedCropRect))
                    put("cachedRequestId", cache.cachedRequestId ?: JSONObject.NULL)
                    put("pendingRequestId", cache.pendingRequestId ?: JSONObject.NULL)
                    put("pendingAgeMillis", cache.pendingAgeMillis ?: JSONObject.NULL)
                    put("lastCompletedRequestId", cache.lastCompletedRequestId ?: JSONObject.NULL)
                    put("lastRequestDurationMillis", cache.lastRequestDurationMillis ?: JSONObject.NULL)
                    put("lastResultTextLength", cache.lastResultTextLength ?: JSONObject.NULL)
                    put("lastResultStatus", cache.lastResultStatus ?: JSONObject.NULL)
                } } ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)
        put("eventChoice", result.eventChoiceSelection?.let { event -> JSONObject().apply {
            put("eventId", event.event.id)
            put("optionCount", event.choices.size)
            put("trusted", event.trusted)
            put("choices", JSONArray().apply {
                event.choices.forEach { visual -> put(JSONObject().apply {
                    put("choiceId", visual.choice.id)
                    put("slot", visual.choice.slot)
                    put("label", visual.choice.name)
                    put("conditionType", visual.choice.conditionType)
                    put("conditionValue", visual.choice.conditionValue)
                    put("button", rectJson(visual.buttonRect))
                    put("buttonConfidence", visual.buttonConfidence)
                    put("enabledConfidence", visual.enabledConfidence)
                }) }
            })
        } } ?: JSONObject.NULL)
        put("eventChoiceDecision", when (val decision = state.eventChoiceDecision) {
            is LabyrinthEventChoiceDecision.Select -> JSONObject().apply {
                put("type", "SELECT")
                put("eventId", decision.eventId)
                put("choiceId", decision.choiceId)
                put("button", rectJson(decision.buttonRect))
                put("actionSafe", decision.actionSafe)
                put("safetyNote", decision.safetyNote ?: JSONObject.NULL)
            }
            is LabyrinthEventChoiceDecision.Wait -> JSONObject().apply {
                put("type", "WAIT")
                put("reason", decision.reason)
            }
            null -> JSONObject.NULL
        })
        put("elapsedMillis", result.elapsedMillis)
        put("stageMillis", JSONObject(result.stageMillis))
        put("nodeSearchMode", result.nodeSearchMode)
        put("nodeSearchWindowCount", result.nodeSearchWindowCount)
        put(
            "trace",
            JSONObject().apply {
                val trace = state.frameTrace
                put("expectedPages", JSONArray(trace.expectedPages))
                put("actionLabel", trace.actionLabel ?: JSONObject.NULL)
                put("actionRejectReason", trace.actionRejectReason ?: JSONObject.NULL)
                put("nodeTargetBlockId", trace.nodeTargetBlockId ?: JSONObject.NULL)
                put("nodeTargetColumn", trace.nodeTargetColumn ?: JSONObject.NULL)
                put("nodeSearchWindowCount", trace.nodeSearchWindowCount)
                put("nodeTrackerReliable", trace.nodeTrackerReliable ?: JSONObject.NULL)
                put("nodeTrackerWorldLeft", trace.nodeTrackerWorldLeft ?: JSONObject.NULL)
                put("nodeTargetExpectedX", trace.nodeTargetExpectedX ?: JSONObject.NULL)
            },
        )
        fun platformJson(matches: List<com.landosol.toolbox.labyrinth.node.FinalBossPlatformMatch>) =
            JSONArray().apply {
                matches.forEach { match -> put(JSONObject().apply {
                    put("left", match.labelRect.left)
                    put("top", match.labelRect.top)
                    put("width", match.labelRect.width)
                    put("height", match.labelRect.height)
                    put("confidence", match.confidence)
                    put("colorScore", match.colorScore)
                    put("purpleActivationScore", match.purpleActivationScore)
                    put("reliable", match.reliable)
                }) }
            }
        put("finalBossPlatforms", platformJson(result.finalBossPlatforms))
        put("finalBossPlatformCandidates", platformJson(result.finalBossPlatformCandidates))
        put("frameWidth", result.frameWidth)
        put("frameHeight", result.frameHeight)
        put(
            "frameAspectRatio",
            if (result.frameWidth > 0 && result.frameHeight > 0) {
                result.frameWidth.toDouble() / result.frameHeight.toDouble()
            } else {
                0.0
            },
        )
        put("receivedFrameCount", state.receivedFrameCount)
        put("recognizedFrameCount", state.frameCount)
        put("actionCount", state.actionCount)
        put("message", state.message ?: JSONObject.NULL)
        put("matchedFeatures", JSONArray(result.matchedFeatures))
        put(
            "pageScores",
            JSONObject().apply {
                result.observation.stateScores.entries
                    .sortedByDescending { it.value }
                    .forEach { (key, value) -> put(key.name, value) }
            },
        )
        put(
            "anchorScores",
            JSONObject().apply {
                result.observation.anchorScores.values.entries
                    .sortedByDescending { it.value }
                    .forEach { (key, value) -> put(key, value) }
            },
        )
        put(
            "boxes",
            JSONArray().apply {
                presentation.boxes.forEach { box ->
                    put(
                        JSONObject().apply {
                            put("left", box.left.toDouble())
                            put("top", box.top.toDouble())
                            put("width", box.width.toDouble())
                            put("height", box.height.toDouble())
                            put("label", box.label)
                            put("recognized", box.recognized)
                            put("selected", box.selected)
                        },
                    )
                }
            },
        )
        put(
            "openingCharacters",
            JSONArray().apply {
                result.openingCharacterMatches.forEach { character ->
                    put(
                        JSONObject().apply {
                            put("slot", character.slotId)
                            put("name", character.displayName ?: JSONObject.NULL)
                            put("id", character.characterId ?: JSONObject.NULL)
                            put("suspectedName", character.suspectedDisplayName ?: JSONObject.NULL)
                            put("suspectedId", character.suspectedCharacterId ?: JSONObject.NULL)
                            put("attribute", character.detectedAttribute?.label ?: JSONObject.NULL)
                            put("confidence", character.confidence)
                            put("margin", character.rivalMargin)
                            put("trusted", character.trusted)
                            put("selected", character.selected)
                        },
                    )
                }
            },
        )
        put(
            "openingViewport",
            result.openingCharacterSelection?.let { viewport ->
                JSONObject().apply {
                    put("state", viewport.recognitionState.name)
                    put("revision", viewport.viewportRevision)
                    put("filter", viewport.currentFilter.label)
                    put("scrollbarVisible", viewport.scrollbar.visible)
                    put("scrollable", viewport.scrollbar.canScroll)
                    put("scrollPosition", viewport.scrollbar.position)
                    put("visibleCharacterCount", viewport.visibleCharacters.size)
                }
            } ?: JSONObject.NULL,
        )
        put(
            "nodeRelicStacks",
            JSONArray().apply {
                result.nodeRelicStackObservation?.entries.orEmpty().forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("attribute", entry.attribute ?: JSONObject.NULL)
                            put("stacks", entry.stacks ?: JSONObject.NULL)
                            put("confidence", entry.iconConfidence)
                            put("evidence", entry.evidenceText ?: JSONObject.NULL)
                        },
                    )
                }
            },
        )
        put(
            "relicChoices",
            JSONArray().apply {
                result.relicChoiceSelection?.choices.orEmpty().forEach { relic ->
                    put(
                        JSONObject().apply {
                            put("name", relic.displayName ?: relic.suspectedDisplayName ?: JSONObject.NULL)
                            put("attribute", relic.attribute ?: relic.suspectedAttribute ?: JSONObject.NULL)
                            put("bonus", relic.attributeBonus ?: JSONObject.NULL)
                            put("currentStacks", relic.currentMarkStacks ?: JSONObject.NULL)
                            put("currentStacksEvidence", relic.currentMarkStacksEvidenceText ?: JSONObject.NULL)
                            put("confidence", relic.confidence)
                            put("margin", relic.rivalMargin)
                            put("recognized", relic.recognized)
                        },
                    )
                }
            },
        )
        put(
            "shop",
            result.shopObservation?.let { shop ->
                JSONObject().apply {
                    put("dialogState", shop.dialogState.name)
                    put("availableItemCount", shop.availableItemCount)
                    put("recognizedButtonCount", shop.recognizedButtonCount)
                    put("refreshButtonEnabled", shop.refreshButtonEnabled)
                    put("refreshButtonEnabledEvidence", shop.refreshButtonEnabledEvidence)
                    put(
                        "items",
                        JSONArray().apply {
                            shop.items.forEach { item ->
                                val relic = item.relicMatch
                                put(
                                    JSONObject().apply {
                                        put("slot", item.slotId)
                                        put("purchasable", item.purchasable)
                                        put("status", item.status.name)
                                        put("kind", item.kind.name)
                                        put("titleText", item.titleText ?: JSONObject.NULL)
                                        put("roleImprintLabel", item.roleImprintLabel ?: JSONObject.NULL)
                                        put("titleEvidenceId", item.titleEvidenceId ?: JSONObject.NULL)
                                        put("categoryState", item.categoryState.name)
                                        put("categoryAttempt", item.categoryAttempt)
                                        put("buyButtonScore", item.buyButtonScore)
                                        put("buyButtonEnabledEvidence", item.buyButtonEnabledEvidence)
                                        put("name", relic?.displayName ?: relic?.suspectedDisplayName ?: JSONObject.NULL)
                                        put("relicId", relic?.relicId ?: relic?.suspectedRelicId ?: JSONObject.NULL)
                                        put("attribute", relic?.attribute ?: relic?.suspectedAttribute ?: JSONObject.NULL)
                                        put("relicConfidence", relic?.confidence ?: 0.0)
                                        put("relicMargin", relic?.rivalMargin ?: 0.0)
                                        put("relicRecognized", relic?.recognized ?: false)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "purchaseCandidate",
                        shop.purchaseCandidate?.let { candidate ->
                            JSONObject().apply {
                                put("relicId", candidate.relicId ?: JSONObject.NULL)
                                put("name", candidate.displayName ?: JSONObject.NULL)
                                put("confidence", candidate.confidence)
                                put("margin", candidate.rivalMargin)
                            }
                        } ?: JSONObject.NULL,
                    )
                }
            } ?: JSONObject.NULL,
        )
        put(
            "characters",
            JSONArray().apply {
                result.characterMatches.forEach { character ->
                    put(
                        JSONObject().apply {
                            put("name", character.displayName ?: character.suspectedDisplayName ?: JSONObject.NULL)
                            put("id", character.characterId ?: character.suspectedCharacterId ?: JSONObject.NULL)
                            put("confidence", character.confidence)
                            put("margin", character.rivalMargin)
                            put("trusted", character.trusted)
                            put("nameEvidence", character.nameEvidenceText ?: JSONObject.NULL)
                            put("nameEvidenceScore", character.nameEvidenceScore)
                            put("nameEvidenceMargin", character.nameEvidenceMargin)
                            put("nameAssisted", character.nameAssisted)
                        },
                    )
                }
            },
        )
        put(
            "nodes",
            JSONArray().apply {
                result.nodeClassifications.forEach { node ->
                    put(
                        JSONObject().apply {
                            put("column", node.column)
                            put("row", node.row)
                            put("type", node.blockType)
                            put("confidence", node.confidence)
                            put("typeConfidence", node.typeConfidence)
                            put("activationScore", maxOf(node.cyanGlowScore, node.purpleGlowScore))
                            put("templateId", node.templateId)
                            put("clickable", node.isClickable)
                        },
                    )
                }
            },
        )
    }

    private companion object {
        const val LOG_TAG = "LabyrinthDebugHttp"
        const val IPV4_LOOPBACK = "127.0.0.1"
        const val DEFAULT_PORT = 8765
        const val JPEG_QUALITY = 68
        const val JPEG_REFRESH_INTERVAL_MILLIS = 750L
        const val FRAME_ARCHIVE_WIDTH = 960
        const val FRAME_ARCHIVE_JPEG_QUALITY = 60
        const val SOCKET_TIMEOUT_MILLIS = 3_000
        const val MAX_HEADER_LINE_BYTES = 8_192
        const val MAX_STATE_HISTORY_ENTRIES = 600
        const val LOGCAT_LINE_LIMIT = 2_000
        const val MAX_LOGCAT_BYTES = 2 * 1024 * 1024

        val DASHBOARD_HTML = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>黎明界实时分析 ${AppVersion.name}</title>
              <style>
                *{box-sizing:border-box} body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",sans-serif;background:#0b1020;color:#e8edf8}
                header{padding:14px 18px;border-bottom:1px solid #27314d;display:flex;gap:18px;align-items:center;position:sticky;top:0;background:#0b1020ee;z-index:3;flex-wrap:wrap}
                .pill{padding:5px 9px;border:1px solid #34415f;border-radius:999px;font-size:13px}.grid{display:grid;grid-template-columns:minmax(620px,1.6fr) minmax(360px,1fr);gap:14px;padding:14px}
                a.action{color:#e8edf8;text-decoration:none;background:#1b2948;border-color:#4f6fa8}a.action:hover{background:#263a63}
                .card{background:#121a2f;border:1px solid #27314d;border-radius:12px;padding:12px;overflow:hidden}.frameWrap{position:relative;width:100%;line-height:0}.frameWrap img{width:100%;height:auto;display:block;border-radius:8px}.box{position:absolute;border:2px solid #57d3ff;pointer-events:none}.box.sel{border-color:#ffd166}.box.bad{border-color:#ff6b6b}.box span{position:absolute;left:0;top:0;transform:translateY(-100%);background:#07101dcc;color:#fff;font:12px/1.25 monospace;padding:2px 4px;white-space:nowrap;max-width:360px;overflow:hidden;text-overflow:ellipsis}
                h2{font-size:15px;margin:0 0 8px}.kv{display:grid;grid-template-columns:120px 1fr;gap:4px 9px;font-size:13px}.kv b{color:#9eb0d3;font-weight:500}pre{white-space:pre-wrap;word-break:break-word;margin:0;font:12px/1.45 ui-monospace,SFMono-Regular,Consolas,monospace;color:#d7e2ff}.stack{display:grid;gap:14px}.json{max-height:380px;overflow:auto}.muted{color:#8292b5;font-size:12px}@media(max-width:1050px){.grid{grid-template-columns:1fr}}
              </style>
            </head>
            <body>
              <header><strong>黎明界实时分析</strong><span class="pill">${AppVersion.label}</span><span class="pill" id="status">等待数据</span><span class="pill" id="page">-</span><span class="muted" id="stamp"></span><a class="pill action" href="/logs.zip">下载日志 ZIP</a><a class="pill action" href="#" id="archiveToggle">逐帧归档：读取中</a></header>
              <main class="grid">
                <section class="card"><div class="frameWrap" id="frameWrap"><img id="frame" alt="latest frame"></div></section>
                <section class="stack">
                  <div class="card"><h2>会话</h2><div class="kv" id="summary"></div></div>
                  <div class="card"><h2>完整分析</h2><pre id="detail"></pre></div>
                  <div class="card"><h2>结构化结果</h2><pre class="json" id="json"></pre></div>
                </section>
              </main>
              <script>
                let frameVersion=-1;
                const esc=s=>String(s??'').replace(/[&<>\"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]));
                function hasSelectionInside(el){const s=window.getSelection?.();if(!s||s.isCollapsed||s.rangeCount===0)return false;return el.contains(s.anchorNode)||el.contains(s.focusNode)}
                function setTextStable(el,value){const next=String(value??'');if(el.textContent===next||hasSelectionInside(el))return;el.textContent=next}
                function setHtmlStable(el,value){const next=String(value??'');if(el.innerHTML===next||hasSelectionInside(el))return;el.innerHTML=next}
                function drawBoxes(boxes){const wrap=document.getElementById('frameWrap');wrap.querySelectorAll('.box').forEach(x=>x.remove());for(const b of boxes||[]){const d=document.createElement('div');d.className='box '+(b.selected?'sel':(!b.recognized?'bad':''));d.style.left=(b.left*100)+'%';d.style.top=(b.top*100)+'%';d.style.width=(b.width*100)+'%';d.style.height=(b.height*100)+'%';const s=document.createElement('span');s.textContent=b.label;d.appendChild(s);wrap.appendChild(d)}}
                let archiveOn=false;
                async function refreshArchive(){try{const r=await fetch('/api/archive',{cache:'no-store'});const a=await r.json();archiveOn=!!a.enabled;const t=document.getElementById('archiveToggle');t.textContent=a.supported?('逐帧归档：'+(a.enabled?'开('+a.frames+')':'关')):'逐帧归档：不可用';}catch(e){}}
                document.getElementById('archiveToggle').addEventListener('click',async ev=>{ev.preventDefault();try{await fetch(archiveOn?'/api/archive/off':'/api/archive/on',{cache:'no-store'});}catch(e){}refreshArchive();});
                refreshArchive();setInterval(refreshArchive,3000);
                async function tick(){try{const r=await fetch('/api/state',{cache:'no-store'});const d=await r.json();setTextStable(document.getElementById('status'),d.status||'-');setTextStable(document.getElementById('page'),(d.page||'-')+' '+Number(d.pageConfidence||0).toFixed(3));setTextStable(document.getElementById('stamp'),new Date(d.timestamp||Date.now()).toLocaleTimeString());setTextStable(document.getElementById('detail'),d.detail||'');const frameSize=(d.frameWidth??0)+' × '+(d.frameHeight??0);setHtmlStable(document.getElementById('summary'),'<b>原始帧</b><span>'+(d.receivedFrameCount??0)+'</span><b>识别帧</b><span>'+(d.recognizedFrameCount??0)+'</span><b>捕获尺寸</b><span>'+frameSize+'</span><b>宽高比</b><span>'+Number(d.frameAspectRatio||0).toFixed(3)+'</span><b>耗时</b><span>'+(d.elapsedMillis??0)+' ms</span><b>动作</b><span>'+(d.actionCount??0)+'</span><b>消息</b><span>'+esc(d.message??'')+'</span>');const compact={trace:d.trace,stageMillis:d.stageMillis,nodeSearchMode:d.nodeSearchMode,nodeSearchWindowCount:d.nodeSearchWindowCount,finalBossPlatforms:d.finalBossPlatforms,finalBossPlatformCandidates:d.finalBossPlatformCandidates,frame:{width:d.frameWidth,height:d.frameHeight,aspectRatio:d.frameAspectRatio},openingViewport:d.openingViewport,nodeRelicStacks:d.nodeRelicStacks,relicChoices:d.relicChoices,characters:d.characters,nodes:d.nodes,pageScores:d.pageScores,anchorScores:d.anchorScores,matchedFeatures:d.matchedFeatures};setTextStable(document.getElementById('json'),JSON.stringify(compact,null,2));drawBoxes(d.boxes);if(d.frameVersion!==frameVersion){frameVersion=d.frameVersion;document.getElementById('frame').src='/frame.jpg?v='+frameVersion}}catch(e){setTextStable(document.getElementById('status'),'连接失败')}finally{setTimeout(tick,500)}}tick();
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
