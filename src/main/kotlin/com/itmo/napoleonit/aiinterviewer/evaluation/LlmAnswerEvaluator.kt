package com.itmo.napoleonit.aiinterviewer.evaluation

import com.itmo.napoleonit.aiinterviewer.config.AppProperties
import com.itmo.napoleonit.aiinterviewer.llm.LlmClient
import com.itmo.napoleonit.aiinterviewer.llm.Schema
import com.itmo.napoleonit.aiinterviewer.web.dto.Confidence
import com.itmo.napoleonit.aiinterviewer.web.dto.Evidence
import com.itmo.napoleonit.aiinterviewer.web.dto.Scores
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * Оценка ответа моделью по рубрике Рамки §8.
 *
 * Цитаты модель выбирает номером сегмента расшифровки, а не текстом с таймкодом:
 * так она не может выдумать ни цитату, ни момент на видео.
 */
@Primary
@Component
class LlmAnswerEvaluator(
    private val llm: LlmClient,
    private val fallback: RuleBasedAnswerEvaluator,
    private val props: AppProperties,
) : AnswerEvaluator {

    override fun evaluate(context: AnswerEvaluationContext): AnswerEvaluationResult {
        val segments = context.segments.mapIndexed { i, s -> "[$i] ${s.text}" }.joinToString("\n")

        val response = llm.completeJson(
            systemPrompt = SYSTEM,
            userPrompt = """
                Вопрос: ${context.questionText}

                Признаки сильного ответа:
                ${context.strongSignals.joinToString("\n") { "- $it" }}

                Расшифровка ответа по сегментам:
                $segments
            """.trimIndent(),
            schemaName = "answer_evaluation",
            schema = SCHEMA,
            type = EvaluationResponse::class.java,
        ) ?: return fallback.evaluate(context)

        val quotes = response.quotes
            .mapNotNull { q -> context.segments.getOrNull(q.segmentIndex)?.let { q to it } }
            .take(3)
            .map { (_, segment) ->
                Evidence(context.answerId, context.questionOrd, segment.text, segment.startMs, segment.endMs)
            }

        return AnswerEvaluationResult(
            scores = Scores(
                technicalCorrectness = response.technicalCorrectness.clamp(),
                depth = response.depth.clamp(),
                relevance = response.relevance.clamp(),
                example = response.example.clamp(),
                personalContribution = response.personalContribution.clamp(),
                scaleAndMetrics = response.scaleAndMetrics.clamp(),
            ),
            confidence = runCatching { Confidence.valueOf(response.confidence) }.getOrDefault(Confidence.LOW),
            comment = response.comment.ifBlank { "Комментарий не сформирован." },
            quotes = quotes,
            model = llm.modelId(),
            promptVersion = PROMPT_VERSION,
        )
    }

    private fun Int?.clamp(): Int? = this?.coerceIn(0, 5)

    data class EvaluationResponse(
        val technicalCorrectness: Int? = null,
        val depth: Int? = null,
        val relevance: Int? = null,
        val example: Int? = null,
        val personalContribution: Int? = null,
        val scaleAndMetrics: Int? = null,
        val confidence: String = "LOW",
        val comment: String = "",
        val quotes: List<QuoteRef> = emptyList(),
    )

    data class QuoteRef(val segmentIndex: Int = 0, val why: String = "")

    private companion object {
        const val PROMPT_VERSION = "eval-v1"

        val CRITERION = mapOf(
            "type" to listOf("integer", "null"),
            "minimum" to 0,
            "maximum" to 5,
            "description" to "0–5, либо null если по расшифровке судить нельзя",
        )

        val SCHEMA = Schema.obj(
            "technicalCorrectness" to CRITERION,
            "depth" to CRITERION,
            "relevance" to CRITERION,
            "example" to CRITERION,
            "personalContribution" to CRITERION,
            "scaleAndMetrics" to CRITERION,
            "confidence" to Schema.enumOf("LOW", "MEDIUM", "HIGH"),
            "comment" to Schema.string("Две-три фразы: что подтверждено, чего не хватило"),
            "quotes" to Schema.array(
                Schema.obj(
                    "segmentIndex" to Schema.integer(0, 200),
                    "why" to Schema.string("Что подтверждает этот фрагмент"),
                )
            ),
        )

        val SYSTEM = """
            Ты оцениваешь один ответ кандидата на техническом интервью по расшифровке аудио.

            Оцени по шести критериям от 0 до 5:
            - technicalCorrectness — техническая корректность сказанного;
            - depth — глубина понимания, а не пересказ определений;
            - relevance — относится ли ответ к заданному вопросу;
            - example — есть ли конкретный пример из практики;
            - personalContribution — раскрыт ли личный вклад кандидата;
            - scaleAndMetrics — названы ли масштаб, результат, метрики.

            Жёсткие правила:
            - оценивай только сказанное. Не додумывай и не дополняй кандидата;
            - если по расшифровке судить нельзя, ставь null, а не низкий балл.
              Отсутствие упоминания навыка не равно отсутствию навыка;
            - низкий балл ставится за неверный или пустой ответ, а не за краткость;
            - расшифровка автоматическая: не снижай оценку за оговорки, обрывы фраз
              и искажения терминов, если смысл понятен;
            - не оценивай манеру речи, акцент, темп, эмоции — только содержание;
            - confidence = LOW, если расшифровка короткая или обрывочная.

            В quotes укажи номера сегментов, подтверждающих твои выводы.

            Отвечай только JSON по схеме.
        """.trimIndent()
    }
}
