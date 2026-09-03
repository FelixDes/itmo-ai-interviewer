package com.itmo.napoleonit.aiinterviewer.evaluation

import com.itmo.napoleonit.aiinterviewer.domain.TranscriptSegment
import com.itmo.napoleonit.aiinterviewer.web.dto.Confidence
import com.itmo.napoleonit.aiinterviewer.web.dto.Evidence
import com.itmo.napoleonit.aiinterviewer.web.dto.Scores
import org.springframework.stereotype.Component
import java.util.UUID

/** Оценка одного ответа (Р-8). Считается сразу и переиспользуется карточкой. */
data class AnswerEvaluationContext(
    val answerId: UUID,
    val questionOrd: Int,
    val questionText: String,
    val strongSignals: List<String>,
    val transcript: String,
    val segments: List<TranscriptSegment>,
)

data class AnswerEvaluationResult(
    val scores: Scores,
    val confidence: Confidence,
    val comment: String,
    val quotes: List<Evidence>,
    val model: String,
    val promptVersion: String,
)

interface AnswerEvaluator {
    fun evaluate(context: AnswerEvaluationContext): AnswerEvaluationResult
}

/**
 * Запасной путь без модели: балл выводится из признаков, которые Рамка §8
 * требует различать — есть ли пример, назван ли личный вклад, приведены ли
 * масштаб и метрики. Каждый вывод подпирается цитатой с таймкодом.
 */
@Component
class RuleBasedAnswerEvaluator : AnswerEvaluator {

    private val personalMarkers = listOf("я ", "мной", "моя", "мой", "лично", "сам ")
    private val exampleMarkers = listOf("например", "у нас", "в проекте", "делали", "настраивали", "использовали")

    override fun evaluate(context: AnswerEvaluationContext): AnswerEvaluationResult {
        val text = context.transcript.lowercase()
        val hasExample = exampleMarkers.any { text.contains(it) }
        val hasPersonal = personalMarkers.any { text.contains(it) }
        val hasNumbers = text.any { it.isDigit() }
        val long = context.transcript.length > 120

        val scores = Scores(
            technicalCorrectness = if (long) 4 else 2,
            depth = if (long && hasExample) 3 else 2,
            relevance = 4,
            example = if (hasExample) 4 else 1,
            personalContribution = if (hasPersonal) 4 else 2,
            scaleAndMetrics = if (hasNumbers) 3 else 1,
        )

        val gaps = buildList {
            if (!hasExample) add("нет конкретного примера")
            if (!hasPersonal) add("не раскрыт личный вклад")
            if (!hasNumbers) add("нет масштаба и метрик")
        }

        return AnswerEvaluationResult(
            scores = scores,
            confidence = if (long) Confidence.MEDIUM else Confidence.LOW,
            comment = if (gaps.isEmpty()) {
                "Ответ по существу: назван механизм, приведён пример и личный вклад."
            } else {
                "Механизмы названы верно, но " + gaps.joinToString(", ") + "."
            },
            quotes = context.segments.take(1).map {
                Evidence(context.answerId, context.questionOrd, it.text, it.startMs, it.endMs)
            },
            model = "rule-based",
            promptVersion = "stub-0",
        )
    }
}
