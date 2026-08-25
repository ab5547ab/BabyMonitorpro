package com.example.babymonitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Foreground service that keeps listening to the microphone even while the
 * screen is off, computes an approximate decibel (SPL) level from raw PCM
 * samples, places a normal phone call on speaker when the noise stays above
 * the configured threshold for the configured duration, and auto-answers
 * (also on speaker) incoming calls from a recognized parent number.
 */
class MonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "baby_monitor_channel"
        const val NOTIFICATION_ID = 1

        const val EXTRA_PHONE = "extra_phone"
        const val EXTRA_PARENT_NUMBER = "extra_parent_number"
        const val EXTRA_THRESHOLD = "extra_threshold"
        const val EXTRA_DURATION = "extra_duration"
        const val ACTION_STOP = "com.example.babymonitor.ACTION_STOP"

        private const val SAMPLE_RATE = 44100

        // Minimum time between two consecutive automatic calls, so the app
        // doesn't dial repeatedly while the baby keeps crying.
        private const val CALL_COOLDOWN_MS = 60_000L

        // Small delay before forcing speakerphone - gives the OS time to
        // actually establish audio routing for the call first.
        private const val SPEAKER_ENABLE_DELAY_MS = 800L
    }

    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    private var phoneNumber: String = ""
    private var parentNumber: String = ""
    private var thresholdDb: Double = 70.0
    private var requiredDurationMs: Long = 5_000L
    private var lastCallTime: Long = 0L
    private var sessionId: String? = null

    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(TelephonyManager.EXTRA_STATE)
            if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                val incomingNumber = intent?.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                handleIncomingCall(incomingNumber)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            phoneStateReceiver,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        phoneNumber = intent?.getStringExtra(EXTRA_PHONE) ?: phoneNumber
        parentNumber = intent?.getStringExtra(EXTRA_PARENT_NUMBER) ?: parentNumber
        thresholdDb = intent?.getDoubleExtra(EXTRA_THRESHOLD, thresholdDb) ?: thresholdDb
        requiredDurationMs = intent?.getLongExtra(EXTRA_DURATION, requiredDurationMs) ?: requiredDurationMs

        val notification = buildNotification("מאזין לרעשים ברקע...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        MonitorState.isRunning.value = true
        MonitorState.statusText.value = "מאזין בבטחה ברקע..."

        if (sessionId == null) {
            sessionId = HistoryRepository.startSession(applicationContext, phoneNumber)
        }

        startListening()
        return START_STICKY
    }

    private fun startListening() {
        job?.cancel()
        job = serviceScope.launch {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) {
                MonitorState.statusText.value = "שגיאה באתחול המיקרופון"
                return@launch
            }

            val bufferSize = minBufferSize * 2
            val audioRecord = createAudioRecord(bufferSize)
            if (audioRecord == null) {
                MonitorState.statusText.value = "אין הרשאת מיקרופון"
                return@launch
            }

            val buffer = ShortArray(bufferSize)
            audioRecord.startRecording()

            var loudSince = -1L
            var peakDbInPeriod = 0.0
            var callTriggeredThisPeriod = false

            try {
                while (true) {
                    val readCount = audioRecord.read(buffer, 0, buffer.size)
                    if (readCount > 0) {
                        val db = calculateDecibels(buffer, readCount)
                        MonitorState.currentDb.value = db

                        val now = System.currentTimeMillis()
                        if (db >= thresholdDb) {
                            if (loudSince < 0) {
                                loudSince = now
                                peakDbInPeriod = db
                                callTriggeredThisPeriod = false
                            }
                            if (db > peakDbInPeriod) peakDbInPeriod = db

                            val elapsedSec = (now - loudSince) / 1000
                            val requiredSec = requiredDurationMs / 1000
                            MonitorState.statusText.value = "מזהה רעש! ($elapsedSec/$requiredSec שנ')"

                            if (!callTriggeredThisPeriod && now - loudSince >= requiredDurationMs) {
                                callTriggeredThisPeriod = true
                                triggerCall()
                            }
                        } else if (loudSince >= 0) {
                            // The loud period just ended - log it as a cry event if it
                            // lasted long enough to have crossed the detection threshold.
                            val totalDurationMs = now - loudSince
                            if (totalDurationMs >= requiredDurationMs) {
                                logCryEvent(loudSince, totalDurationMs, peakDbInPeriod)
                            }
                            loudSince = -1L
                            peakDbInPeriod = 0.0
                            MonitorState.statusText.value = "מאזין בבטחה ברקע..."
                        }
                    }
                }
            } finally {
                audioRecord.stop()
                audioRecord.release()
            }
        }
    }

    /**
     * Tries microphone sources in order of "best far-field sensitivity first".
     * VOICE_COMMUNICATION is the same source used for speakerphone/VoIP calls
     * and applies automatic gain control (AGC), which boosts quiet/distant
     * sound the way you'd want from across a room - unlike the plain MIC
     * source, which is tuned closer to close-mouth call levels on many
     * devices. CAMCORDER and MIC are kept as fallbacks for devices/ROMs that
     * restrict VOICE_COMMUNICATION outside of an actual call.
     */
    private fun createAudioRecord(bufferSize: Int): AudioRecord? {
        val sourcesToTry = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sourcesToTry) {
            try {
                val record = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    tuneAudioEffects(record.audioSessionId)
                    return record
                } else {
                    record.release()
                }
            } catch (e: SecurityException) {
                return null
            } catch (e: Exception) {
                // try next source
            }
        }
        return null
    }

    /**
     * VOICE_COMMUNICATION brings automatic gain control along with it, which
     * is exactly the "boosted, far-field" behavior we want. It also enables
     * echo cancellation and noise suppression by default though, and those
     * are designed to filter out exactly the kind of sustained ambient sound
     * we're trying to detect - so we explicitly turn those two off while
     * leaving gain control alone.
     */
    private fun tuneAudioEffects(audioSessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(audioSessionId)?.enabled = false
            }
        } catch (e: Exception) {
            // best-effort - not all devices expose these effects
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(audioSessionId)?.enabled = false
            }
        } catch (e: Exception) {
            // best-effort
        }
    }

    private suspend fun logCryEvent(startedAt: Long, durationMs: Long, peakDb: Double) {
        val sid = sessionId ?: return
        withContext(Dispatchers.IO) {
            HistoryRepository.addCryEvent(
                applicationContext,
                sid,
                CryEvent(
                    timestamp = startedAt,
                    durationSec = (durationMs / 1000).toInt().coerceAtLeast(1),
                    peakDb = peakDb.toInt()
                )
            )
        }
    }

    /** Approximates sound pressure level in dB from a block of 16-bit PCM samples. */
    private fun calculateDecibels(buffer: ShortArray, readCount: Int): Double {
        var sum = 0.0
        for (i in 0 until readCount) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / readCount)
        if (rms < 1.0) return 0.0
        return 20 * log10(rms)
    }

    private fun triggerCall() {
        val now = System.currentTimeMillis()
        if (now - lastCallTime < CALL_COOLDOWN_MS) return
        if (phoneNumber.isBlank()) return
        lastCallTime = now

        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)
            updateNotification("בוצעה שיחה אוטומטית ל-$phoneNumber")

            // Force speakerphone shortly after dialing so whoever answers can
            // hear the room clearly and talk back hands-free.
            serviceScope.launch {
                delay(SPEAKER_ENABLE_DELAY_MS)
                enableSpeakerphone()
            }
        } catch (e: SecurityException) {
            MonitorState.statusText.value = "אין הרשאת חיוג (CALL_PHONE)"
        }
    }

    /**
     * Auto-answers (and switches to speaker) only when the incoming number
     * matches the configured parent number, and only if ANSWER_PHONE_CALLS
     * was granted. Any other caller rings normally and is left untouched.
     */
    private fun handleIncomingCall(number: String?) {
        if (number.isNullOrBlank() || parentNumber.isBlank()) return
        if (normalizePhoneNumber(number) != normalizePhoneNumber(parentNumber)) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            val telecomManager = getSystemService(TelecomManager::class.java)
            telecomManager?.acceptRingingCall()
            updateNotification("שיחה נענתה אוטומטית מההורה")

            serviceScope.launch {
                delay(SPEAKER_ENABLE_DELAY_MS)
                enableSpeakerphone()
            }
        } catch (e: SecurityException) {
            // Permission was checked above but some OEMs still restrict this - ignore.
        }
    }

    /**
     * Best-effort speakerphone toggle. This uses the same public AudioManager
     * API regular apps have always used for this; behavior can still vary
     * across OEM skins and newer Android versions that further restrict
     * non-dialer apps from controlling ongoing call audio routing.
     */
    private fun enableSpeakerphone() {
        try {
            val audioManager = getSystemService(AudioManager::class.java)
            audioManager?.mode = AudioManager.MODE_IN_CALL
            audioManager?.isSpeakerphoneOn = true
        } catch (e: Exception) {
            // best-effort
        }
    }

    /** Compares only the last 9 digits so +972.../0... formatting differences don't matter. */
    private fun normalizePhoneNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    private fun stopMonitoring() {
        job?.cancel()
        sessionId?.let { sid ->
            serviceScope.launch(Dispatchers.IO) {
                HistoryRepository.endSession(applicationContext, sid)
            }
        }
        sessionId = null
        MonitorState.isRunning.value = false
        MonitorState.statusText.value = "ממתין להפעלה"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, MonitorService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("שמרטף חכם")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPendingIntent)
            .addAction(0, "עצור", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ניטור שמרטף",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "התראה קבועה בזמן שהניטור פעיל ברקע"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        try {
            unregisterReceiver(phoneStateReceiver)
        } catch (e: Exception) {
            // already unregistered / never registered - ignore
        }
        sessionId?.let { sid ->
            CoroutineScope(Dispatchers.IO).launch {
                HistoryRepository.endSession(applicationContext, sid)
            }
        }
        sessionId = null
        MonitorState.isRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
