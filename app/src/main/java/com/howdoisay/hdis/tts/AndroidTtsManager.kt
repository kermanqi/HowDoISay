package com.howdoisay.hdis.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class AndroidTtsManager(context: Context) : TextToSpeech.OnInitListener {
    private val textToSpeech = TextToSpeech(context.applicationContext, this)
    private var ready = false

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS && textToSpeech.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE
    }

    fun speakOrStop(text: String): Boolean {
        if (!ready) return false
        return if (textToSpeech.isSpeaking) {
            textToSpeech.stop()
            true
        } else {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hdis-english") == TextToSpeech.SUCCESS
        }
    }

    fun stop() {
        textToSpeech.stop()
    }

    fun close() {
        stop()
        textToSpeech.shutdown()
    }
}
