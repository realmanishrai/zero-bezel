package com.realmanishrai.zero_bezel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
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
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class MediaProjectionService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var lastFrameTimeMs = 0L
    private var captureWidth = 1
    private var captureHeight = 1
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())

        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == 0 || resultData == null) {
            stopCapture()
            return START_NOT_STICKY
        }

        startCapture(resultCode, resultData)
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture(releaseService = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        stopCapture(releaseService = false)

        val projectionManager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: run {
                stopCapture()
                return
            }
        mediaProjection = projection

        val metrics = resources.displayMetrics
        captureWidth = (metrics.widthPixels / 2).coerceAtLeast(1)
        captureHeight = (metrics.heightPixels / 2).coerceAtLeast(1)

        val thread = HandlerThread("ScreenExtenderCapture").also { it.start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)

        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            IMAGE_READER_MAX_IMAGES
        ).also { reader ->
            reader.setOnImageAvailableListener(
                { availableReader -> handleFrameAvailable(availableReader) },
                captureHandler
            )
        }

        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture(stopProjection = false)
                }
            },
            captureHandler
        )

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenExtenderHostCapture",
            captureWidth,
            captureHeight,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )
    }

    private fun handleFrameAvailable(reader: ImageReader) {
        try {
            val now = System.currentTimeMillis()
            val image = reader.acquireLatestImage() ?: return

            if (now - lastFrameTimeMs < FRAME_INTERVAL_MS) {
                image.close()
                return
            }
            lastFrameTimeMs = now

            serviceScope.launch {
                processFrame(image)
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Failed while acquiring screen frame.", exception)
        }
    }

    private fun processFrame(image: Image) {
        var bitmap: Bitmap? = null
        try {
            bitmap = image.toBitmapWithRowPadding(captureWidth, captureHeight)
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, FRAME_JPEG_QUALITY, outputStream)

            val frame = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            val frameMessage = JSONObject()
                .put(KEY_TYPE, TYPE_FRAME)
                .put(KEY_DATA, frame)
                .toString()
            ScreenFrameBus.publish(frameMessage)
        } catch (exception: Exception) {
            Log.e(TAG, "Failed while processing screen frame.", exception)
        } finally {
            bitmap?.recycle()
            image.close()
        }
    }

    private fun Image.toBitmapWithRowPadding(width: Int, height: Int): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        return Bitmap.createBitmap(
            paddedWidth,
            height,
            Bitmap.Config.ARGB_8888
        ).also { bitmap ->
            bitmap.copyPixelsFromBuffer(buffer)
        }
    }

    private fun stopCapture(
        stopProjection: Boolean = true,
        releaseService: Boolean = true
    ) {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        val projection = mediaProjection
        mediaProjection = null
        if (stopProjection) {
            projection?.stop()
        }

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        if (releaseService) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen sharing",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenExtender")
            .setContentText("Sharing this screen with the connected client")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.realmanishrai.zero_bezel.START_MEDIA_PROJECTION"
        const val ACTION_STOP = "com.realmanishrai.zero_bezel.STOP_MEDIA_PROJECTION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "screen_extender_projection"
        private const val NOTIFICATION_ID = 42
        private const val KEY_TYPE = "type"
        private const val KEY_DATA = "data"
        private const val TYPE_FRAME = "FRAME"
        private const val FRAME_INTERVAL_MS = 100L
        private const val FRAME_JPEG_QUALITY = 50
        private const val IMAGE_READER_MAX_IMAGES = 2
        private const val TAG = "MediaProjectionService"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, MediaProjectionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, MediaProjectionService::class.java)
                .setAction(ACTION_STOP)
        }
    }
}
