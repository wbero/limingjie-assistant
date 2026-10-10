package com.landosol.toolbox.automation.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

class MediaProjectionCaptureService : Service() {
    private val projectionManager by lazy {
        getSystemService(MediaProjectionManager::class.java)
    }
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }
    private val windowManager by lazy {
        getSystemService(WindowManager::class.java)
    }
    private val displayManager by lazy {
        getSystemService(DisplayManager::class.java)
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            postDisplayReconfiguration()
        }
    }
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var callback: MediaProjection.Callback? = null
    private var frameThread: HandlerThread? = null
    private var lastFrameAt = 0L
    private var captureStartedAt = 0L
    /** ImageReader buffers must not be closed while a callback is copying a frame. */
    private val captureLock = Any()
    /** Only ever touched inside [captureLock], one frame conversion at a time. */
    private val frameScratch = CaptureFrameScratch()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture("用户停止屏幕捕获")
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        postDisplayReconfiguration()
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        stopCapture("捕获服务销毁")
        CaptureStateRegistry.clear()
        super.onDestroy()
    }

    private fun startCapture(intent: Intent) {
        if (projection != null) return
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val data = intent.parcelableExtra<Intent>(EXTRA_DATA) ?: run {
            stopCapture("缺少系统截图授权数据")
            return
        }
        if (resultCode == Int.MIN_VALUE) {
            stopCapture("缺少系统截图授权结果")
            return
        }
        try {
            startAsForeground()
            val mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                ?: error("系统未返回屏幕投影")
            projection = mediaProjection
            captureStartedAt = System.currentTimeMillis()
            Log.i("LandosolCapture", "projection-start sdk=${Build.VERSION.SDK_INT} elapsedMillis=${SystemClock.elapsedRealtime()}")
            val thread = HandlerThread("landosol-capture").also { it.start() }
            frameThread = thread
            val projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    // Android provides no reason argument; do not infer a specific user action.
                    Log.w("LandosolCapture", "projection-onStop elapsedMillis=${SystemClock.elapsedRealtime()} captureStartedAt=$captureStartedAt")
                    stopCapture("系统撤销屏幕捕获授权")
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    Log.i("LandosolCapture", "projection-content-resize width=$width height=$height elapsedMillis=${SystemClock.elapsedRealtime()}")
                }

                override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
                    Log.i("LandosolCapture", "projection-content-visibility visible=$isVisible elapsedMillis=${SystemClock.elapsedRealtime()}")
                }
            }
            callback = projectionCallback
            mediaProjection.registerCallback(projectionCallback, Handler(thread.looper))
            awaitStableInitialDisplay(mediaProjection, thread)
        } catch (error: Throwable) {
            stopCapture("启动屏幕捕获失败：${error.message ?: "未知错误"}")
        }
    }

    /**
     * Returning from Android's capture picker can bring the landscape game to the foreground a few
     * milliseconds after this service starts. Creating the VirtualDisplay immediately therefore
     * races the old portrait toolbox metrics against the new landscape game metrics. Since a
     * MediaProjection token may create only one VirtualDisplay on Android 14+, wait for consecutive
     * equal samples before consuming the token.
     */
    private fun awaitStableInitialDisplay(mediaProjection: MediaProjection, thread: HandlerThread) {
        val handler = Handler(thread.looper)
        val stabilityGate = CaptureDisplayStabilityGate()
        val sampler = object : Runnable {
            override fun run() {
                if (projection !== mediaProjection || virtualDisplay != null) return
                val stableMetrics = stabilityGate.observe(currentDisplayMetrics())
                if (stableMetrics == null) {
                    if (stabilityGate.exhausted) {
                        stopCapture(
                            "屏幕捕获仍为竖屏；请确认模拟器允许自动旋转，并在横屏的助手或游戏界面重新授权",
                        )
                        return
                    }
                    handler.postDelayed(this, INITIAL_DISPLAY_SAMPLE_INTERVAL_MILLIS)
                    return
                }
                runCatching { configureDisplay(mediaProjection, thread, stableMetrics) }
                    .onFailure { error ->
                        stopCapture("启动屏幕捕获失败：${error.message ?: "未知错误"}")
                    }
            }
        }
        handler.post(sampler)
    }

    private fun reconfigureDisplayIfNeeded() {
        val snapshot = synchronized(captureLock) { projection to frameThread }
        snapshot.first ?: return
        snapshot.second ?: return
        val metrics = currentDisplayMetrics()
        val readerSize = synchronized(captureLock) {
            imageReader?.let { reader -> reader.width to reader.height }
        } ?: return
        if (readerSize.first == metrics.width && readerSize.second == metrics.height) return

        // Android 14+ does not allow creating a second VirtualDisplay from the same projection
        // token. Reusing the old display with resize()/setSurface() looks valid on AOSP, but MuMu
        // can keep the old mirror transform and render the new landscape game into only one corner
        // of the larger buffer. That produces a syntactically valid Bitmap which every downstream
        // recognizer then reports as UNKNOWN. Fail closed instead: once the physical display size
        // changes, require a fresh MediaProjection authorization while the game is already in its
        // final orientation.
        stopCapture(
            "屏幕尺寸从${readerSize.first}x${readerSize.second}变为${metrics.width}x${metrics.height}，" +
                "为避免投影画面缩在角落，请保持游戏当前方向并重新授权屏幕捕获",
        )
    }

    private fun postDisplayReconfiguration() {
        val thread = frameThread ?: return
        Handler(thread.looper).post { reconfigureDisplayIfNeeded() }
    }

    private fun configureDisplay(
        mediaProjection: MediaProjection,
        thread: HandlerThread,
        metrics: CaptureDisplayMetrics = currentDisplayMetrics(),
    ) {
        synchronized(captureLock) {
            val width = metrics.width
            val height = metrics.height
            val density = metrics.densityDpi
            Log.i("LandosolCapture", "configure-display width=$width height=$height densityDpi=$density flags=${captureDisplayFlags()} elapsedMillis=${SystemClock.elapsedRealtime()}")
            require(width > 0 && height > 0) { "系统返回了无效的屏幕尺寸" }

            val handler = Handler(thread.looper)
            val reader = ImageReader.newInstance(
                width,
                height,
                android.graphics.PixelFormat.RGBA_8888,
                2,
            )
            val oldReader = imageReader
            val existingDisplay = virtualDisplay
            if (existingDisplay == null) {
                mediaProjection.createVirtualDisplay(
                    "LandosolToolboxCapture",
                    width,
                    height,
                    density,
                    captureDisplayFlags(),
                    reader.surface,
                    null,
                    handler,
                ).also { created ->
                    virtualDisplay = created
                }
            } else {
                // Android 14+ permits only one createVirtualDisplay() call per MediaProjection
                // instance. Rotation therefore must reuse the original VirtualDisplay instead of
                // releasing it and creating a second one with the same projection token.
                oldReader?.setOnImageAvailableListener(null, null)
                existingDisplay.resize(width, height, density)
                existingDisplay.setSurface(reader.surface)
            }
            imageReader = reader
            if (oldReader !== reader) {
                oldReader?.close()
            }
            lastFrameAt = 0L
            reader.setOnImageAvailableListener({ source -> onImageAvailable(source) }, handler)
            setState(
                CaptureState.Running(
                    width = width,
                    height = height,
                    startedAt = captureStartedAt,
                    // MediaProjection's VirtualDisplay is only the capture output. Input must
                    // target the real display being mirrored, not this synthetic display id.
                    gestureDisplayId = Display.DEFAULT_DISPLAY,
                ),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayMetrics(): CaptureDisplayMetrics {
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        if (defaultDisplay != null) {
            val metrics = DisplayMetrics().also(defaultDisplay::getRealMetrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return CaptureDisplayMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return CaptureDisplayMetrics(bounds.width(), bounds.height(), resources.displayMetrics.densityDpi)
        }
        val metrics = DisplayMetrics().also(windowManager.defaultDisplay::getRealMetrics)
        return CaptureDisplayMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private fun onImageAvailable(source: ImageReader) {
        val now = System.currentTimeMillis()
        var bitmap: Bitmap? = null
        var conversionError: Throwable? = null
        synchronized(captureLock) {
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
            if (now - lastFrameAt < FRAME_INTERVAL_MILLIS) {
                runCatching { image.close() }
            } else {
                lastFrameAt = now
                try {
                    bitmap = imageToBitmap(image)
                } catch (error: Throwable) {
                    conversionError = error
                } finally {
                    // Close the acquired image before another thread can close/rebuild the reader.
                    runCatching { image.close() }
                }
            }
        }
        bitmap?.let { CaptureFrameBus.dispatch(CapturedFrame(it, now)) }
        conversionError?.let { error ->
            stopCapture("读取屏幕帧失败：${error.message ?: "未知错误"}")
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes.firstOrNull() ?: error("屏幕帧没有像素平面")
        val pixels = rgba8888ToArgbPixels(
            width = image.width,
            height = image.height,
            pixelStride = plane.pixelStride,
            rowStride = plane.rowStride,
            buffer = plane.buffer,
            // createBitmap below copies these pixels, so the buffer is free again on return.
            scratch = frameScratch,
        )
        return Bitmap.createBitmap(
            pixels,
            image.width,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(com.landosol.toolbox.R.string.capture_notification_title))
            .setContentText(getString(com.landosol.toolbox.R.string.capture_notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopCapture(reason: String) {
        val hadResources: Boolean
        synchronized(captureLock) {
            hadResources = projection != null || virtualDisplay != null || imageReader != null
            Log.w("LandosolCapture", "capture-stop reason=$reason hadResources=$hadResources " +
                "elapsedMillis=${SystemClock.elapsedRealtime()} captureStartedAt=$captureStartedAt " +
                "reader=${imageReader?.width}x${imageReader?.height}")
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
            virtualDisplay?.release()
            virtualDisplay = null
            callback?.let { projection?.unregisterCallback(it) }
            callback = null
            projection?.stop()
            projection = null
        }
        frameThread?.quitSafely()
        frameThread = null
        captureStartedAt = 0L
        if (hadResources) setState(CaptureState.Stopped(reason))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setState(value: CaptureState) {
        _state.value = value
        CaptureStateRegistry.update(value)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(com.landosol.toolbox.R.string.capture_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private inline fun <reified T : Parcelable> Intent.parcelableExtra(key: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, T::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }

    companion object {
        private const val ACTION_START = "com.landosol.toolbox.capture.START"
        private const val ACTION_STOP = "com.landosol.toolbox.capture.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "landosol-capture"
        private const val NOTIFICATION_ID = 4101
        /**
         * Minimum interval between two converted screen frames.
         *
         * The recognition session consumes at most one frame per 500 ms. Converting every 100 ms
         * produced an 8.3 MB pixel buffer plus an 8.3 MB bitmap five times per consumed frame,
         * all recycled immediately: the four 2026-09-20 bundles show ~350 GC events and ~10.6 s of
         * GC pause per run with the heap pinned at 176/200 MB. 300 ms still stays ahead of the
         * 500 ms consumer, so frame freshness is unchanged.
         */
        private const val FRAME_INTERVAL_MILLIS = 300L
        private const val INITIAL_DISPLAY_SAMPLE_INTERVAL_MILLIS = 100L
        // 公共镜像显示器会进入 Accessibility 的有效显示器集合；否则 MuMu 将游戏输入
        // 克隆到捕获显示器后，无障碍只能把手势发往不可触达游戏的默认显示器。
        private fun captureDisplayFlags(): Int =
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                } else {
                    0
                }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MediaProjectionCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val stopped = context.stopService(
                Intent(context, MediaProjectionCaptureService::class.java),
            )
            if (!stopped) CaptureStateRegistry.clear()
        }
    }

}

internal data class CaptureDisplayMetrics(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

/** Pure state gate kept separate so the portrait-to-landscape startup race is JVM-testable. */
internal class CaptureDisplayStabilityGate(
    private val requiredStableSamples: Int = 3,
    private val maxSamples: Int = 15,
) {
    private var previous: CaptureDisplayMetrics? = null
    private var stableSampleCount = 0
    private var totalSampleCount = 0

    val exhausted: Boolean
        get() = totalSampleCount >= maxSamples

    init {
        require(requiredStableSamples > 0)
        require(maxSamples >= requiredStableSamples)
    }

    fun observe(metrics: CaptureDisplayMetrics): CaptureDisplayMetrics? {
        totalSampleCount++
        stableSampleCount = if (metrics == previous) stableSampleCount + 1 else 1
        previous = metrics
        // Every recognizer and action reference is calibrated against a landscape game frame.
        // A device configured as 1080x1920 is supported by rotating the activity/display to
        // 1920x1080; accepting the pre-rotation portrait metrics would consume the one-shot
        // MediaProjection token and strand the run on UNKNOWN after the game rotates.
        return metrics.takeIf {
            metrics.width > metrics.height && stableSampleCount >= requiredStableSamples
        }
    }
}

/**
 * Copies an ImageReader RGBA_8888 plane into Android ARGB pixel integers.
 *
 * ImageReader rows may contain padding. Reading only the validated row bytes avoids passing the
 * Image's native buffer directly to Bitmap.copyPixelsFromBuffer(), which can crash in native
 * code when the reader is being closed or its buffer limit does not include the row padding.
 */
internal fun rgba8888ToArgbPixels(
    width: Int,
    height: Int,
    pixelStride: Int,
    rowStride: Int,
    buffer: ByteBuffer,
    scratch: CaptureFrameScratch? = null,
): IntArray {
    require(width > 0 && height > 0) { "屏幕帧尺寸无效：${width}x$height" }
    require(pixelStride >= 4) { "RGBA 像素步长无效：$pixelStride" }
    require(rowStride >= width * pixelStride) {
        "RGBA 行步长无效：rowStride=$rowStride pixelStride=$pixelStride width=$width"
    }

    val lastByteExclusive = (height - 1).toLong() * rowStride +
        (width - 1).toLong() * pixelStride + 4L
    require(lastByteExclusive <= buffer.limit().toLong()) {
        "RGBA 缓冲区长度不足：需要$lastByteExclusive，实际${buffer.limit()}"
    }

    val source = buffer.duplicate().apply { position(0) }
    val rowBytes = scratch?.rowBytes(width * pixelStride) ?: ByteArray(width * pixelStride)
    val pixels = scratch?.pixels(width * height) ?: IntArray(width * height)
    for (row in 0 until height) {
        source.position(row * rowStride)
        source.get(rowBytes, 0, rowBytes.size)
        var sourceOffset = 0
        var pixelOffset = row * width
        repeat(width) {
            val red = rowBytes[sourceOffset].toInt() and 0xff
            val green = rowBytes[sourceOffset + 1].toInt() and 0xff
            val blue = rowBytes[sourceOffset + 2].toInt() and 0xff
            val alpha = rowBytes[sourceOffset + 3].toInt() and 0xff
            pixels[pixelOffset] =
                (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            sourceOffset += pixelStride
            pixelOffset++
        }
    }
    return pixels
}

/**
 * Reusable per-frame conversion buffers.
 *
 * A 1920x1080 frame needs an 8.3 MB int[] and a ~7.7 KB row buffer, and a fresh pair per frame is
 * what exhausted the heap in the 2026-09-20 report ("Failed to allocate a 8294416 byte
 * allocation"). Both buffers are write-before-read for every pixel of every frame and the int[] is
 * copied by Bitmap.createBitmap before the next frame is converted, so neither survives the call
 * as live data and both can be pooled. Reuse is confined to one capture callback at a time by the
 * caller's lock; a size change simply reallocates.
 */
internal class CaptureFrameScratch {
    private var pixels = IntArray(0)
    private var rowBytes = ByteArray(0)

    fun pixels(size: Int): IntArray {
        if (pixels.size != size) pixels = IntArray(size)
        return pixels
    }

    fun rowBytes(size: Int): ByteArray {
        if (rowBytes.size != size) rowBytes = ByteArray(size)
        return rowBytes
    }
}

object CaptureStateRegistry {
    private val state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    @Volatile
    private var lastStopReason: String? = null

    fun observe(): StateFlow<CaptureState> = state.asStateFlow()

    fun isActive(): Boolean = state.value is CaptureState.Running

    fun lastStopReason(): String? = lastStopReason

    fun gestureDisplayId(): Int =
        (state.value as? CaptureState.Running)?.gestureDisplayId ?: Display.DEFAULT_DISPLAY

    internal fun update(value: CaptureState) {
        when (value) {
            is CaptureState.Running -> lastStopReason = null
            is CaptureState.Stopped -> lastStopReason = value.reason
            CaptureState.Idle -> Unit
        }
        state.value = value
    }

    internal fun clear() {
        // Keep lastStopReason until the next successful Running state. The service calls clear()
        // from onDestroy immediately after reporting Stopped, and erasing the reason here makes
        // post-mortem first-frame diagnostics impossible.
        state.value = CaptureState.Idle
    }
}
