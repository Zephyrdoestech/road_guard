package com.wecare.roadguard.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/** Explicit, foreground voice sessions. Never listens continuously or sends an unconfirmed transcript. */
class VoiceController(private val context: Context, private val status: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var startedAt = 0L
    private var onRecorded: ((File, Long) -> Unit)? = null
    private var player: MediaPlayer? = null
    private var playbackFile: File? = null
    private var ttsReady = false
    private var stopped = false
    private var generation = 0
    private val tts = TextToSpeech(context) { result -> ttsReady = result == TextToSpeech.SUCCESS }
    private val timeout = Runnable { finishRecording() }
    fun dictate(language: String, draft: (String) -> Unit, confirmed: (String) -> Unit) {
        stop(); stopped = false
        if (!ttsReady) { status("Speech playback is not ready. Try again shortly."); return }
        listen(language) { spoken ->
            draft(spoken)
            val session = generation
            tts.language = Locale.forLanguageTag(language)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) { main.post { status("Readback failed. Your message was not sent.") } }
                override fun onDone(utteranceId: String?) { main.post {
                    if (stopped || session != generation) return@post
                    status("Say Send to confirm, or Cancel.")
                    listen(language) { answer ->
                        if (answer.trim().lowercase() in setOf("send", "send message", "ipadala")) { confirmed(spoken); status("Message queued for delivery.") }
                        else status("Not sent. Your transcript is kept as a draft.")
                    }
                } }
            })
            status("Reading your message back…")
            tts.speak("$spoken. Say Send to send this message, or Cancel.", TextToSpeech.QUEUE_FLUSH, null, "confirm-$session")
        }
    }
    private fun listen(language: String, result: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { status("Speech recognition is unavailable on this phone."); return }
        recognizer?.destroy()
        val session = generation
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { status("Listening…") }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { status("Processing speech…") }
                @Deprecated("Required by Android's RecognitionListener API")
                override fun onError(error: Int) { status("Speech was not captured (code $error). Nothing was sent; try again.") }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!stopped && session == generation && !spoken.isNullOrBlank()) result(spoken)
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1))
        }
    }
    @Suppress("DEPRECATION")
    fun record(done: (File, Long) -> Unit) {
        stop(); stopped = false
        val directory = File(context.filesDir, "voice").apply { mkdirs() }
        audioFile = File.createTempFile("note", ".m4a", directory)
        try {
            recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(64_000); setAudioSamplingRate(44_100)
                setMaxDuration(60_000); setOutputFile(audioFile!!.absolutePath); prepare(); start()
            }
            onRecorded = done; startedAt = SystemClock.elapsedRealtime()
            main.postDelayed(timeout, 59_500); status("Recording • maximum 60 seconds. Tap Stop to keep a draft.")
        } catch (_: Exception) { stop(); status("Microphone recording is unavailable. Check permission and try again.") }
    }
    fun finishRecording() {
        val active = recorder ?: return
        main.removeCallbacks(timeout)
        val duration = (SystemClock.elapsedRealtime() - startedAt).coerceAtMost(60_000)
        try {
            active.stop(); active.release(); recorder = null
            val file = audioFile!!; audioFile = null
            onRecorded?.invoke(file, duration); onRecorded = null
            status("Voice draft ready. Send it when ready.")
        } catch (_: Exception) { stop(); status("Recording was too short. Please try again.") }
    }
    fun play(file: File, completed: () -> Unit) {
        stop(); stopped = false; playbackFile = file
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath); setOnCompletionListener { completed(); stop(); status("Playback finished.") }
                prepare(); start()
            }; status("Playing voice note…")
        } catch (_: Exception) { stop(); status("Voice note could not be played.") }
    }
    fun stop() {
        generation++; stopped = true; main.removeCallbacks(timeout)
        recognizer?.cancel(); recognizer?.destroy(); recognizer = null; tts.stop()
        runCatching { recorder?.stop() }; recorder?.release(); recorder = null
        audioFile?.delete(); audioFile = null; onRecorded = null
        player?.release(); player = null; playbackFile?.delete(); playbackFile = null
    }
    fun close() { stop(); tts.shutdown() }
}
