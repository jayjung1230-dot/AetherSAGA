package com.aether.reader.features.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * TextToSpeech controller with display hardware profile awareness.
 * - LCD/OLED tablets & phones: Allows speech reading directly via built-in speaker or any connected audio output.
 * - E-Ink devices: Requires Bluetooth audio or connected earphones/headset to prevent unintended loud playback.
 */
class TtsEngineController(
    private val ctx: Context,
    private val allowSpeaker: () -> Boolean = { false },
    private val onPageDone: () -> Unit
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    var speaking = false
        private set
    var isPaused = false
        private set
    var currentSpeed: Float = 1.0f
        private set
    var currentPitch: Float = 1.0f
        private set
    var currentVoiceName: String = ""
        private set

    var onSentenceDone: ((String) -> Unit)? = null
    var onSentenceStart: ((String) -> Unit)? = null

    fun setSpeed(rate: Float) {
        currentSpeed = rate.coerceIn(0.5f, 2.0f)
        if (ready) {
            tts?.setSpeechRate(currentSpeed)
        }
    }

    fun setPitch(pitch: Float) {
        currentPitch = pitch.coerceIn(0.5f, 2.0f)
        if (ready) {
            tts?.setPitch(currentPitch)
        }
    }

    fun setDialogueMode(isDialogue: Boolean, targetPitch: Float) {
        if (ready) {
            tts?.setPitch(targetPitch.coerceIn(0.5f, 2.0f))
        }
    }

    fun setVoiceByName(voiceName: String) {
        currentVoiceName = voiceName
        if (ready && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (voiceName.isBlank()) {
                tts?.voice = tts?.defaultVoice
            } else {
                val found = tts?.voices?.find { it.name == voiceName }
                if (found != null) {
                    tts?.voice = found
                }
            }
        }
    }

    fun getAvailableVoices(): List<Voice> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return tts?.voices?.filter {
                val loc = it.locale
                loc.language == "ko" || loc.language == "en" || loc == Locale.getDefault()
            }?.sortedBy { it.name } ?: emptyList()
        }
        return emptyList()
    }

    fun speakSample(text: String, voiceName: String? = null, pitch: Float? = null) {
        ensureInitialized {
            pitch?.let { tts?.setPitch(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !voiceName.isNullOrBlank()) {
                tts?.voices?.find { it.name == voiceName }?.let { tts?.voice = it }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sample_preview")
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        }
    }

    fun ensureInitialized(onReady: () -> Unit = {}) {
        if (ready) {
            onReady()
            return
        }
        if (tts == null) {
            tts = TextToSpeech(ctx) { st ->
                ready = st == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.getDefault()
                    tts?.setSpeechRate(currentSpeed)
                    tts?.setPitch(currentPitch)
                    if (currentVoiceName.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        tts?.voices?.find { it.name == currentVoiceName }?.let { tts?.voice = it }
                    }
                    val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    tts?.setAudioAttributes(attrs)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) { id?.let { onSentenceStart?.invoke(it) } }
                        override fun onError(id: String?) { speaking = false }
                        override fun onDone(id: String?) {
                            if (speaking) {
                                if (id != null && id != "page" && onSentenceDone != null) {
                                    onSentenceDone?.invoke(id)
                                } else {
                                    onPageDone()
                                }
                            }
                        }
                    })
                    onReady()
                }
            }
        }
    }

    fun isBtAudioConnected(): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }

    fun isHeadsetConnected(): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    fun isAudioOutputAvailable(): Boolean {
        if (allowSpeaker()) return true
        return isBtAudioConnected() || isHeadsetConnected()
    }

    fun speakSentence(text: String, utteranceId: String): Boolean {
        if (!isAudioOutputAvailable()) return false
        speaking = true
        isPaused = false
        if (tts == null) {
            tts = TextToSpeech(ctx) { st ->
                ready = st == TextToSpeech.SUCCESS
                if (!ready) {
                    speaking = false
                    return@TextToSpeech
                }
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(currentSpeed)
                tts?.setPitch(currentPitch)
                if (currentVoiceName.isNotBlank() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    tts?.voices?.find { it.name == currentVoiceName }?.let { tts?.voice = it }
                }
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(attrs)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        id?.let { onSentenceStart?.invoke(it) }
                    }
                    override fun onError(id: String?) {
                        speaking = false
                    }
                    override fun onDone(id: String?) {
                        if (speaking) {
                            if (id != null && id != "page" && onSentenceDone != null) {
                                onSentenceDone?.invoke(id)
                            } else {
                                onPageDone()
                            }
                        }
                    }
                })
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        } else if (ready) {
            tts?.setSpeechRate(currentSpeed)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
        return true
    }

    fun speak(text: String): Boolean = speakSentence(text, "page")

    fun pause() {
        speaking = false
        isPaused = true
        tts?.stop()
    }

    fun stop() {
        speaking = false
        isPaused = false
        tts?.stop()
    }

    fun shutdown() {
        speaking = false
        isPaused = false
        tts?.shutdown()
        tts = null
        ready = false
    }
}
