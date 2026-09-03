package com.itmo.napoleonit.aiinterviewer.transcription

import com.itmo.napoleonit.aiinterviewer.domain.TranscriptSegment
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import kotlin.math.absoluteValue

/**
 * Заглушка ASR: выдаёт правдоподобный русский ответ с таймкодами, чтобы карточка
 * и перемотка видео по цитате работали на реальных данных. Здесь встанет вызов
 * сервиса asr:8100.
 */
@Component
class StubAsrEngine(
    /** Настоящий ASR думает секунды: без задержки фронту нечего показывать на экране ожидания. */
    @Value("\${app.asr.stub-delay-ms:2500}") private val delayMs: Long,
) : AsrEngine {

    private val variants = listOf(
        listOf(
            "Использовали кафку на продакшене, сообщения хранятся в самой кафке." to 0L,
            "Дублирование настроено девопсами, у каждого сообщения есть гуид." to 6_200L,
            "Плюс паттерн аутбокс, мы гарантируем, что сообщение отправится." to 12_400L,
        ),
        listOf(
            "Большие объёмы загружали через файлы, заливали их в таблицу." to 0L,
            "Там были файлы на несколько гигов, вставка шла батчами по тысяче строк." to 5_800L,
            "Если запрос медленный, всегда смотрим эксплейн, сначала эксплейн анализ." to 11_500L,
        ),
        listOf(
            "Пайплайн настраивали в гитлаб, там линтеры и тесты на этапе сборки." to 0L,
            "Дальше пересобираем контейнер и отправляем его в регистри." to 5_400L,
            "Откат делали через откатывающий реквест либо средствами гитлаба." to 10_900L,
        ),
    )

    override fun transcribe(mediaKey: String, contentType: String?): AsrResult {
        if (delayMs > 0) Thread.sleep(delayMs)
        val variant = variants[mediaKey.hashCode().absoluteValue % variants.size]
        val segments = variant.map { (text, start) ->
            TranscriptSegment(startMs = start, endMs = start + 5_500, text = text)
        }
        return AsrResult(
            text = segments.joinToString(" ") { it.text },
            segments = segments,
            model = "stub-asr",
        )
    }
}
