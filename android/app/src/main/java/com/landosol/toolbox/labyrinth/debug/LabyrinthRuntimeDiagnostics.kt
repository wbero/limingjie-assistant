package com.landosol.toolbox.labyrinth.debug

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import com.landosol.toolbox.AppVersion
import com.landosol.toolbox.automation.accessibility.LandosolAccessibilityService
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.capture.CaptureState
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import java.util.ArrayDeque
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** Samples on its own thread, including while recognition is stalled or stopped. */
class LabyrinthRuntimeDiagnostics(context: Context) {
    private val context = context.applicationContext
    private val started = AtomicBoolean(false)
    private val samples = ArrayDeque<String>()
    private var previousElapsedMillis: Long? = null
    private var previousCpuMillis: Long? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "labyrinth-runtime-diagnostics").apply { isDaemon = true }
        }.scheduleWithFixedDelay(
            { runCatching { sample() }.onFailure { Log.w(LOG_TAG, "runtime sample failed", it) } },
            0L,
            SAMPLE_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    @Synchronized
    private fun sample(): JSONObject {
        val elapsed = SystemClock.elapsedRealtime()
        val cpu = Process.getElapsedCpuTime()
        val interval = previousElapsedMillis?.let { elapsed - it }
        val cpuDelta = previousCpuMillis?.let { cpu - it }
        val runtime = Runtime.getRuntime()
        val capture = CaptureStateRegistry.observe().value
        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("elapsedRealtimeMillis", elapsed)
            put("pid", Process.myPid())
            put("processStartElapsedRealtimeMillis", Process.getStartElapsedRealtime())
            put("processAgeMillis", elapsed - Process.getStartElapsedRealtime())
            put("processCpuMillis", cpu)
            put("sampleIntervalMillis", interval ?: JSONObject.NULL)
            // A multi-threaded process can exceed 100%; this is not the host PC's CPU usage.
            put("processCpuPercentOfOneCore", if (interval != null && interval > 0 && cpuDelta != null && cpuDelta >= 0) {
                cpuDelta * 100.0 / interval
            } else JSONObject.NULL)
            put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
            put("javaHeapCommittedBytes", runtime.totalMemory())
            put("javaHeapMaxBytes", runtime.maxMemory())
            put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
            runCatching {
                val memory = Debug.MemoryInfo()
                Debug.getMemoryInfo(memory)
                put("processPssKiB", memory.totalPss)
                put("processPrivateDirtyKiB", memory.totalPrivateDirty)
            }.onFailure { put("processMemoryError", it.javaClass.simpleName) }
            runCatching {
                val memory = ActivityManager.MemoryInfo()
                context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
                put("androidTotalMemoryBytes", memory.totalMem)
                put("androidAvailableMemoryBytes", memory.availMem)
                put("androidLowMemory", memory.lowMemory)
                put("androidLowMemoryThresholdBytes", memory.threshold)
            }.onFailure { put("androidMemoryError", it.javaClass.simpleName) }
            put("accessibilityConnected", LandosolAccessibilityService.isConnected())
            put("foregroundPackage", LandosolAccessibilityService.observedForegroundPackage() ?: JSONObject.NULL)
            put("foregroundActivity", LandosolAccessibilityService.foregroundActivity() ?: JSONObject.NULL)
            put("capture", JSONObject().apply {
                put("state", when (capture) {
                    CaptureState.Idle -> "IDLE"
                    is CaptureState.Running -> "RUNNING"
                    is CaptureState.Stopped -> "STOPPED"
                })
                put("lastStopReason", CaptureStateRegistry.lastStopReason() ?: JSONObject.NULL)
                put("consumer", CaptureFrameBus.currentOwner() ?: JSONObject.NULL)
                if (capture is CaptureState.Running) {
                    put("width", capture.width)
                    put("height", capture.height)
                    put("startedAt", capture.startedAt)
                    put("gestureDisplayId", capture.gestureDisplayId)
                }
            })
        }
        previousElapsedMillis = elapsed
        previousCpuMillis = cpu
        samples.addLast(json.toString())
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
        return json
    }

    fun environmentJson(): ByteArray = JSONObject().apply {
        put("schemaVersion", 1)
        put("appVersion", AppVersion.name)
        put("appVersionCode", AppVersion.code)
        put("targetSdk", context.applicationInfo.targetSdkVersion)
        put("android", JSONObject().apply {
            put("release", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("securityPatch", Build.VERSION.SECURITY_PATCH)
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("hardware", Build.HARDWARE)
            put("fingerprint", Build.FINGERPRINT)
            put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("availableProcessors", Runtime.getRuntime().availableProcessors())
            put("uptimeMillis", SystemClock.uptimeMillis())
            put("elapsedRealtimeMillis", SystemClock.elapsedRealtime())
            put("timeZone", TimeZone.getDefault().id)
        })
        val resources = context.resources
        put("configuration", JSONObject().apply {
            put("densityDpi", resources.configuration.densityDpi)
            put("density", resources.displayMetrics.density.toDouble())
            put("fontScale", resources.configuration.fontScale.toDouble())
            put("screenWidthDp", resources.configuration.screenWidthDp)
            put("screenHeightDp", resources.configuration.screenHeightDp)
            put("orientation", resources.configuration.orientation)
            put("locales", resources.configuration.locales.toLanguageTags())
        })
        runCatching {
            put("displays", JSONArray().apply {
                context.getSystemService(DisplayManager::class.java).displays.forEach { display ->
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    display.getRealMetrics(metrics)
                    put(JSONObject().apply {
                        put("id", display.displayId)
                        put("name", display.name)
                        put("state", display.state)
                        put("rotation", display.rotation)
                        put("widthPixels", metrics.widthPixels)
                        put("heightPixels", metrics.heightPixels)
                        put("densityDpi", metrics.densityDpi)
                        put("density", metrics.density.toDouble())
                        put("reportedXdpi", metrics.xdpi.toDouble())
                        put("reportedYdpi", metrics.ydpi.toDouble())
                        put("refreshRateHz", display.refreshRate.toDouble())
                    })
                }
            })
        }.onFailure { put("displayError", it.javaClass.simpleName) }
        runCatching {
            val service = ComponentName(context, LandosolAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString)
            put("permissions", JSONObject().apply {
                put("accessibilityServiceEnabledInSettings", service in enabled)
                put("accessibilityServiceConnected", LandosolAccessibilityService.isConnected())
            })
        }.onFailure { put("permissionError", it.javaClass.simpleName) }
        put("sampling", JSONObject().apply {
            put("intervalMillis", SAMPLE_INTERVAL_MILLIS)
            put("maxSamples", MAX_SAMPLES)
            put("scope", "android_guest_and_this_process; host_pc_metrics_unavailable")
        })
        put("runtimeAtExport", sample())
    }.toString(2).toByteArray(Charsets.UTF_8)

    @Synchronized
    fun historyNdjson(): ByteArray = samples.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)

    private companion object {
        const val SAMPLE_INTERVAL_MILLIS = 10_000L
        const val MAX_SAMPLES = 360
        const val LOG_TAG = "LabyrinthDiagnostics"
    }
}
