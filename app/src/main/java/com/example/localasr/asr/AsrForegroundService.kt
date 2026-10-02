package com.example.localasr.asr

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import com.example.localasr.MainActivity
import com.example.localasr.R
import com.example.localasr.history.HistoryDatabase
import com.example.localasr.model.ModelManager
import com.example.localasr.model.ModelCatalog
import com.example.localasr.model.RecognitionMode

class AsrForegroundService : Service(), SpeechRecorderListener {
    private var recorder: SpeechRecorder? = null
    private var historyDatabase: HistoryDatabase? = null
    private var sessionId = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationUpdate = 0L
    private var recordingStartedAt = 0L
    private var recognitionMode = RecognitionMode.STREAMING

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecognition()
            ACTION_START -> startRecognition()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        recorder?.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startRecognition() {
        if (isActive) return
        isActive = true
        currentTranscript = ""
        lastSavedSessionId = 0L
        currentStatus = "正在加载模型…"
        recordingStartedAt = 0L
        startForeground(NOTIFICATION_ID, buildNotification(currentStatus))
        acquireWakeLock()
        publishState(forceNotification = true)

        val modelManager = ModelManager(applicationContext)
        val model = modelManager.selectedModel
        if (!modelManager.isInstalled(model)) {
            finishWithError("模型未安装或校验信息已丢失")
            return
        }
        recognitionMode = model.mode

        historyDatabase = HistoryDatabase(applicationContext)
        recorder = SpeechRecorder(
            context = applicationContext,
            model = model,
            modelDirectory = modelManager.modelDirectory(model),
            speakerModelDirectory = ModelCatalog.speakerEmbedding
                .takeIf { modelManager.isInstalled(it) }
                ?.let { modelManager.modelDirectory(it) },
            listener = this,
        ).also { it.start() }
    }

    private fun stopRecognition() {
        if (!isActive) {
            stopSelf()
            return
        }
        currentStatus = "正在整理最后一段文字…"
        publishState(forceNotification = true)
        recorder?.stop()
    }

    override fun onReady(startedAt: Long) {
        recordingStartedAt = startedAt
        sessionId = historyDatabase?.beginSession(startedAt) ?: 0L
        currentStatus = when (recognitionMode) {
            RecognitionMode.STREAMING -> "正在后台实时转写"
            RecognitionMode.OFFLINE -> "正在聆听，停顿后显示文字"
        }
        publishState(forceNotification = true, startedAt = startedAt)
    }

    override fun onTranscriptChanged(text: String) {
        currentTranscript = text
        publishState(forceNotification = false)
    }

    override fun onSegmentFinalized(text: String) {
        currentTranscript = text
        if (sessionId != 0L) {
            historyDatabase?.updateSession(sessionId, System.currentTimeMillis(), text)
        }
        publishState(forceNotification = false)
    }

    override fun onFinished(text: String, startedAt: Long, endedAt: Long) {
        currentTranscript = text
        if (text.isBlank()) {
            if (sessionId != 0L) historyDatabase?.delete(sessionId)
            lastSavedSessionId = 0L
            currentStatus = "已停止，没有识别到文字"
        } else {
            if (sessionId == 0L) sessionId = historyDatabase?.beginSession(startedAt) ?: 0L
            if (sessionId != 0L) historyDatabase?.updateSession(sessionId, endedAt, text)
            lastSavedSessionId = sessionId
            currentStatus = "已停止并保存到历史记录"
        }
        finishService()
    }

    override fun onError(message: String) {
        if (sessionId != 0L) {
            if (currentTranscript.isBlank()) {
                historyDatabase?.delete(sessionId)
            } else {
                historyDatabase?.updateSession(sessionId, System.currentTimeMillis(), currentTranscript)
            }
        }
        finishWithError("转写中断：$message")
    }

    private fun finishWithError(message: String) {
        currentStatus = message
        finishService()
    }

    private fun finishService() {
        isActive = false
        recorder = null
        publishState(forceNotification = false)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        historyDatabase?.close()
        historyDatabase = null
        sessionId = 0L
        stopSelf()
    }

    private fun publishState(forceNotification: Boolean, startedAt: Long = 0L) {
        if (startedAt > 0L) recordingStartedAt = startedAt
        sendBroadcast(
            Intent(ACTION_STATE_CHANGED).setPackage(packageName),
            STATE_PERMISSION,
        )
        if (!isActive) return
        val now = System.currentTimeMillis()
        if (forceNotification || now - lastNotificationUpdate >= NOTIFICATION_UPDATE_INTERVAL_MS) {
            val notification = buildNotification(currentStatus, recordingStartedAt)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            lastNotificationUpdate = now
        }
    }

    private fun buildNotification(status: String, startedAt: Long = 0L): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, AsrForegroundService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentTitle("本地语音转文字")
            .setContentText(status)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply {
                if (startedAt > 0L) {
                    setWhen(startedAt)
                    setUsesChronometer(true)
                }
            }
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_mic_notification),
                    "停止并保存",
                    stopPendingIntent,
                ).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "实时转写",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示长时间语音转写的运行状态"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:asr-recording",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "com.example.localasr.action.START_ASR"
        const val ACTION_STOP = "com.example.localasr.action.STOP_ASR"
        const val ACTION_STATE_CHANGED = "com.example.localasr.action.ASR_STATE_CHANGED"
        const val STATE_PERMISSION = "com.example.localasr.permission.ASR_STATE"

        @Volatile
        var isActive: Boolean = false
            private set

        @Volatile
        var currentTranscript: String = ""
            private set

        @Volatile
        var currentStatus: String = ""
            private set

        @Volatile
        var lastSavedSessionId: Long = 0L
            private set

        fun updateLastSavedTranscript(sessionId: Long, text: String) {
            if (!isActive && lastSavedSessionId == sessionId) currentTranscript = text
        }

        private const val CHANNEL_ID = "asr_recording"
        private const val NOTIFICATION_ID = 41
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 2_000L
    }
}
