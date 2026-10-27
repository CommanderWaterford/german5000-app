package com.vocab5000.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.speech.tts.TextToSpeech
import com.vocab5000.core.model.MediaLookup
import java.io.FileNotFoundException
import java.util.Locale

enum class PlaybackOutcome { PLAYED_RECORDING, PLAYED_SPEECH_FALLBACK, UNAVAILABLE }

/**
 * Plays packaged audio offline (spec §6.2): immediate start, cancels the previous clip,
 * requests transient audio focus and never autoplays on its own. Optional on-device
 * text-to-speech is a fallback only and is labelled as such in the UI.
 */
class AudioPlayer(
    context: Context,
    private val media: MediaLookup,
    private val speechLocaleTag: String,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun hasRecording(audioId: String?): Boolean {
        val path = audioId?.let(media::audioPath) ?: return false
        return try {
            appContext.assets.openFd(path).close()
            true
        } catch (_: FileNotFoundException) {
            false
        } catch (_: java.io.IOException) {
            false
        }
    }

    fun play(audioId: String?, fallbackText: String?, slow: Boolean, allowSpeechFallback: Boolean): PlaybackOutcome {
        stop()
        val path = audioId?.let(media::audioPath)
        if (path != null && hasRecording(audioId)) {
            val afd = appContext.assets.openFd(path)
            val mp = MediaPlayer()
            mp.setAudioAttributes(attributes)
            afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            mp.setOnPreparedListener {
                if (slow) it.playbackParams = PlaybackParams().setSpeed(SLOW_SPEED).setPitch(1f)
                if (requestFocus()) it.start()
            }
            mp.setOnCompletionListener { stop() }
            mp.prepareAsync()
            player = mp
            return PlaybackOutcome.PLAYED_RECORDING
        }
        if (allowSpeechFallback && !fallbackText.isNullOrBlank()) {
            speak(fallbackText, slow)
            return PlaybackOutcome.PLAYED_SPEECH_FALLBACK
        }
        return PlaybackOutcome.UNAVAILABLE
    }

    private fun speak(text: String, slow: Boolean) {
        val engine = tts
        if (engine == null) {
            tts = TextToSpeech(appContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.forLanguageTag(speechLocaleTag)
                    speak(text, slow)
                }
            }
            return
        }
        if (!ttsReady) return
        engine.setSpeechRate(if (slow) SLOW_SPEED else 1f)
        if (requestFocus()) engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "g5000")
    }

    private fun requestFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) stop() }
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Stops playback; called on navigation and session exit. */
    fun stop() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            it.release()
        }
        player = null
        tts?.stop()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        const val SLOW_SPEED = 0.75f
    }
}
