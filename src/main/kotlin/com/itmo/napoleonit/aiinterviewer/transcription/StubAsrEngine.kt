package com.itmo.napoleonit.aiinterviewer.transcription

import com.itmo.napoleonit.aiinterviewer.domain.TranscriptSegment
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Заглушка ASR: выдаёт правдоподобный русский ответ с таймкодами.
 *
 * Текст подбирается по теме вопроса, а не случайно: иначе оценщик справедливо
 * ставит нулевую релевантность и сквозной прогон перестаёт что-либо показывать.
 * Здесь встанет вызов сервиса asr:8100.
 */
@Component
class StubAsrEngine(
    /** Настоящий ASR думает секунды: без задержки фронту нечего показывать на экране ожидания. */
    @Value("\${app.asr.stub-delay-ms:2500}") private val delayMs: Long,
) : AsrEngine, QuestionAwareAsr {

    private data class Variant(val keywords: List<String>, val lines: List<String>)

    private val variants = listOf(
        Variant(
            listOf("поиск", "мотивац", "ищете", "проектом"),
            listOf(
                "Сейчас в поиске работы, потому что на текущем месте достиг потолка по задачам и росту.",
                "Хочется проектов, где есть интеграции: контракты, работа с внешними системами, шины.",
                "Готов идти в команду, где можно влиять на архитектуру, а не только закрывать тикеты.",
            ),
        ),
        Variant(
            listOf("kafka", "кафк", "очеред", "rabbit", "сообщен"),
            listOf(
                "Использовали кафку на продакшене, сообщения хранятся в самой кафке.",
                "Дублирование настроено девопсами, у каждого сообщения есть гуид для дедупликации.",
                "Плюс паттерн аутбокс, мы гарантируем, что сообщение отправится, а необработанные уходят в отдельную очередь.",
            ),
        ),
        Variant(
            listOf("postgres", "постгрес", "данных", "запрос", "объём"),
            listOf(
                "Большие объёмы загружали через файлы, заливали их в таблицу.",
                "Там были файлы на несколько гигов, вставка шла батчами по тысяче строк.",
                "Если запрос медленный, всегда смотрим эксплейн, сначала эксплейн анализ, потом уже индексы.",
            ),
        ),
        Variant(
            listOf("ci/cd", "пайплайн", "docker", "деплой", "откат", "контейнер"),
            listOf(
                "Пайплайн настраивали в гитлаб, там линтеры и тесты на этапе сборки.",
                "Дальше пересобираем контейнер и отправляем его в регистри.",
                "Откат делали через откатывающий реквест либо средствами гитлаба.",
            ),
        ),
        Variant(
            listOf("асинхрон", "синхрон", "блокиру", "async"),
            listOf(
                "Асинхронный код выбираю, когда много IO операций, а вычислений мало.",
                "На проекте обрабатывал торговые операции: много обращений к брокеру и к базе.",
                "Если внутри асинхронного кода вызвать блокирующую функцию, встанет весь событийный цикл.",
            ),
        ),
        Variant(
            listOf("ии", " ai", "нейросет", "искусственн"),
            listOf(
                "ИИ использую, чтобы ускорить разработку: сверяюсь по подходу к задаче.",
                "Прошу сгенерить кусок кода, но обязательно разбираюсь, как он работает.",
                "Свой код тоже показываю модели, чтобы найти то, что упустил.",
            ),
        ),
    )

    private val fallback = Variant(
        emptyList(),
        listOf(
            "На прошлом месте занимался похожими задачами, но подробностей уже не вспомню.",
            "В основном работал в команде, конкретные решения принимали вместе с тимлидом.",
        ),
    )

    override fun transcribe(mediaKey: String, contentType: String?): AsrResult {
        if (delayMs > 0) Thread.sleep(delayMs)
        val hint = questionHints[mediaKey].orEmpty().lowercase()
        val variant = variants.firstOrNull { v -> v.keywords.any { hint.contains(it) } } ?: fallback

        val segments = variant.lines.mapIndexed { index, text ->
            val start = index * 6_200L
            TranscriptSegment(startMs = start, endMs = start + 5_500, text = text)
        }
        return AsrResult(
            text = segments.joinToString(" ") { it.text },
            segments = segments,
            model = "stub-asr",
        )
    }

    private val questionHints = HashMap<String, String>()

    override fun rememberQuestion(mediaKey: String, questionText: String) {
        questionHints[mediaKey] = questionText
    }
}
