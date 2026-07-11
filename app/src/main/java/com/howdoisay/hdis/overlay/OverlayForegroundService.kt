package com.howdoisay.hdis.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.howdoisay.hdis.R
import com.howdoisay.hdis.data.ArkEnglishExpressionService
import com.howdoisay.hdis.data.ExpressionException
import com.howdoisay.hdis.data.SecureCredentialStore
import com.howdoisay.hdis.data.toExpressionError
import com.howdoisay.hdis.data.WavAudioRecorder
import com.howdoisay.hdis.domain.ExpressionError
import com.howdoisay.hdis.domain.ExpressionPipeline
import com.howdoisay.hdis.domain.ResultState
import com.howdoisay.hdis.domain.userMessage
import com.howdoisay.hdis.tts.AndroidTtsManager
import com.howdoisay.hdis.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs

class OverlayForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var credentialStore: SecureCredentialStore
    private lateinit var recorder: WavAudioRecorder
    private lateinit var tts: AndroidTtsManager
    private val expressionService = ArkEnglishExpressionService()
    private val expressionPipeline = ExpressionPipeline(expressionService)

    private var bubble: TextView? = null
    private var card: LinearLayout? = null
    private var cardTitle: TextView? = null
    private var cardActions: LinearLayout? = null
    private var state: ResultState = ResultState.Idle
    private var recorderReady = false
    private var stopRequested = false
    private var recordingAttempt = 0L
    private var recordingJob: Job? = null
    private var pipelineJob: Job? = null
    private var timeoutJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        credentialStore = SecureCredentialStore(this)
        recorder = WavAudioRecorder(this)
        tts = AndroidTtsManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            else -> {
                startForeground(NOTIFICATION_ID, notification())
                if (bubble == null) showBubble()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pipelineJob?.cancel()
        recordingAttempt += 1
        timeoutJob?.cancel()
        runBlocking { recorder.cancel() }
        removeCard()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        tts.close()
        serviceScope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun showBubble() {
        val view = TextView(this).apply {
            text = "HDIS"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = circle(Color.rgb(43, 101, 255))
            elevation = dp(8).toFloat()
            setOnClickListener { onBubbleTapped() }
            setOnTouchListener(BubbleDragListener())
        }
        val params = overlayParams(dp(56), dp(56)).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = dp(12)
        }
        windowManager.addView(view, params)
        bubble = view
    }

    private fun onBubbleTapped() {
        if (state is ResultState.Listening || state is ResultState.Creating) return
        if (!credentialStore.read().isReady()) {
            showCard(ResultState.Failure(ExpressionError.MissingConfiguration))
            return
        }
        beginRecording()
    }

    private fun beginRecording() {
        pipelineJob?.cancel()
        timeoutJob?.cancel()
        val attempt = ++recordingAttempt
        recorderReady = false
        stopRequested = false
        state = OverlayReducer.reduce(state, OverlayAction.Start)
        showCard(state)
        recordingJob = serviceScope.launch {
            val result = recorder.start(serviceScope)
            if (attempt != recordingAttempt) {
                recorder.cancel()
                return@launch
            }
            result.fold(
                onSuccess = {
                    recorderReady = true
                    if (stopRequested) {
                        finishRecording(attempt)
                    } else {
                        timeoutJob = launch {
                            delay(MAX_RECORDING_MILLIS)
                            if (attempt == recordingAttempt && state is ResultState.Listening) stopRecording()
                        }
                    }
                },
                onFailure = { showFailure(ExpressionError.ProviderFailure("Microphone unavailable")) }
            )
        }
    }

    private fun stopRecording() {
        if (state !is ResultState.Listening) return
        timeoutJob?.cancel()
        state = OverlayReducer.reduce(state, OverlayAction.Stop)
        showCard(state)
        stopRequested = true
        if (recorderReady) finishRecording(recordingAttempt)
    }

    private fun finishRecording(attempt: Long) {
        if (attempt != recordingAttempt || !recorderReady) return
        recorderReady = false
        pipelineJob = serviceScope.launch {
            val audio = recorder.stop()
            if (audio == null || audio.length() <= WAV_MINIMUM_BYTES) {
                showFailure(ExpressionError.NoSpeech)
                audio?.delete()
                return@launch
            }
            translate(audio)
        }
    }

    private suspend fun translate(audio: File) {
        try {
            val credentials = credentialStore.read()
            val english = expressionPipeline.translate(audio, credentials).getOrElse { throw it }
            audio.delete()
            state = OverlayReducer.reduce(state, OverlayAction.Completed(english))
            showCard(state)
        } catch (_: CancellationException) {
            audio.delete()
        } catch (failure: Throwable) {
            audio.delete()
            showFailure(
                (failure as? ExpressionException)?.error ?: failure.toExpressionError()
            )
        }
    }

    private fun showFailure(error: ExpressionError) {
        state = OverlayReducer.reduce(state, OverlayAction.Failed(error))
        showCard(state)
    }

    private fun showCard(newState: ResultState) {
        val root = card ?: createCard().also { created ->
            card = created
            val metrics = resources.displayMetrics
            val params = overlayParams((metrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(32)
            }
            windowManager.addView(created, params)
        }
        cardTitle?.text = when (newState) {
            ResultState.Idle -> ""
            ResultState.Listening -> "Listening…"
            ResultState.Creating -> "Creating English…"
            is ResultState.Success -> newState.english
            is ResultState.Failure -> newState.error.userMessage()
        }
        cardTitle?.textSize = if (newState is ResultState.Success) 22f else 18f
        cardActions?.removeAllViews()
        when (newState) {
            ResultState.Listening -> {
                addButton("Stop") { stopRecording() }
                addButton("Cancel") { cancelAndHide() }
            }
            ResultState.Creating -> addButton("Cancel") { cancelAndHide() }
            is ResultState.Success -> {
                addButton("Copy") { copy(newState.english) }
                addButton("Speak") {
                    if (!tts.speakOrStop(newState.english)) toast("English voice is unavailable")
                }
                addButton("Again") { beginRecording() }
                addButton("Close") { cancelAndHide() }
            }
            is ResultState.Failure -> {
                addButton("Again") { beginRecording() }
                addButton("Close") { cancelAndHide() }
            }
            ResultState.Idle -> Unit
        }
        root.visibility = View.VISIBLE
    }

    private fun createCard(): LinearLayout {
        val padding = dp(20)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, dp(12))
            background = rounded(Color.argb(235, 20, 22, 27), dp(22))
            elevation = dp(12).toFloat()
            addView(TextView(this@OverlayForegroundService).apply {
                setTextColor(Color.WHITE)
                setLineSpacing(dp(4).toFloat(), 1f)
                textSize = 18f
            }.also { cardTitle = it })
            addView(LinearLayout(this@OverlayForegroundService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(14), 0, 0)
            }.also { cardActions = it })
        }
    }

    private fun addButton(label: String, onClick: () -> Unit) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(45, 255, 255, 255), dp(12))
            setOnClickListener { onClick() }
        }
        cardActions?.addView(button, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            dp(42)
        ).apply { marginStart = dp(6) })
    }

    private fun cancelAndHide() {
        pipelineJob?.cancel()
        timeoutJob?.cancel()
        recordingAttempt += 1
        recorderReady = false
        stopRequested = false
        serviceScope.launch { recorder.cancel() }
        state = OverlayReducer.reduce(state, OverlayAction.Close)
        tts.stop()
        removeCard()
    }

    private fun removeCard() {
        card?.let { runCatching { windowManager.removeView(it) } }
        card = null
        cardTitle = null
        cardActions = null
    }

    private fun copy(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("English expression", text))
        toast("Copied")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun notification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, OverlayForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ))
    }

    private inner class BubbleDragListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var downX = 0f
        private var downY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = view.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y; downX = event.rawX; downY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (downX - event.rawX).toInt()
                    params.y = initialY + (event.rawY - downY).toInt()
                    windowManager.updateViewLayout(view, params)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downX) > dp(8) || abs(event.rawY - downY) > dp(8)
                    if (moved) {
                        params.gravity = Gravity.CENTER_VERTICAL or if (event.rawX < resources.displayMetrics.widthPixels / 2) Gravity.START else Gravity.END
                        params.x = dp(12)
                        windowManager.updateViewLayout(view, params)
                    } else view.performClick()
                    return true
                }
            }
            return false
        }
    }

    companion object {
        const val ACTION_START = "com.howdoisay.hdis.action.START"
        const val ACTION_STOP = "com.howdoisay.hdis.action.STOP"
        private const val CHANNEL_ID = "hdis_bubble"
        private const val NOTIFICATION_ID = 2001
        private const val MAX_RECORDING_MILLIS = 30_000L
        private const val WAV_MINIMUM_BYTES = 200L

        fun start(context: Context) {
            val intent = Intent(context, OverlayForegroundService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
