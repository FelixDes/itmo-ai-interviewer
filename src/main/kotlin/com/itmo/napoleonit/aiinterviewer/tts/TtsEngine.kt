package com.itmo.napoleonit.aiinterviewer.tts

import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Озвучка вопроса (Р-16, Р-22). Здесь встанет Silero на tts:8101. */
interface TtsEngine {
    /** voice = null означает голос по умолчанию. */
    fun synthesize(text: String, voice: String? = null): ByteArray
    val contentType: String
    val model: String
}

/**
 * Заглушка: WAV-тишина, длительность пропорциональна длине текста.
 * Позволяет фронту строить плеер против рабочего эндпоинта.
 */
@Component
class SilenceTtsEngine : TtsEngine {

    override val contentType = "audio/wav"
    override val model = "stub-tts"

    override fun synthesize(text: String, voice: String?): ByteArray {
        val seconds = (text.length / 15).coerceIn(2, 30)
        val sampleRate = 8000
        val dataSize = sampleRate * seconds * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)             // PCM
        header.putShort(1)             // моно
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)  // байт в секунду
        header.putShort(2)             // выравнивание блока
        header.putShort(16)            // бит на сэмпл
        header.put("data".toByteArray())
        header.putInt(dataSize)
        val out = ByteArrayOutputStream(44 + dataSize)
        out.write(header.array())
        out.write(ByteArray(dataSize))
        return out.toByteArray()
    }
}
