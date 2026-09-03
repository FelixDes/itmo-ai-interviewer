package com.itmo.napoleonit.aiinterviewer.questions

import org.springframework.stereotype.Component

/**
 * Решение об уточняющем вопросе (Р-1).
 *
 * Триггеры из Рамки §5: ответ общий и без примера, не раскрыт личный вклад,
 * нет масштаба и метрик, противоречие резюме, нужна проверка глубины.
 */
data class FollowUpContext(
    val questionText: String,
    val strongSignals: List<String>,
    val transcript: String,
    val followUpsForThisAnswer: Int,
    val maxFollowUpsPerAnswer: Int,
    val totalQuestions: Int,
    val maxTotalQuestions: Int,
)

sealed interface FollowUpDecision {
    data class Ask(val text: String, val strongSignals: List<String>) : FollowUpDecision
    data object Proceed : FollowUpDecision
}

interface FollowUpGenerator {
    fun decide(context: FollowUpContext): FollowUpDecision
}

/**
 * Заглушка вместо LLM, но правило настоящее: уточняем, когда в ответе нет
 * ни чисел, ни признаков личного вклада. Лимиты соблюдаются те же, что будут
 * у боевой реализации.
 */
@Component
class StubFollowUpGenerator : FollowUpGenerator {

    private val personalMarkers = listOf("я ", "мной", "моя", "мой", "лично", "сам ")

    override fun decide(context: FollowUpContext): FollowUpDecision {
        if (context.followUpsForThisAnswer >= context.maxFollowUpsPerAnswer) return FollowUpDecision.Proceed
        if (context.totalQuestions >= context.maxTotalQuestions) return FollowUpDecision.Proceed

        val text = context.transcript.lowercase()
        val hasNumbers = text.any { it.isDigit() }
        val hasPersonal = personalMarkers.any { text.contains(it) }
        if (hasNumbers && hasPersonal) return FollowUpDecision.Proceed

        val missing = buildList {
            if (!hasPersonal) add("какой именно была ваша роль")
            if (!hasNumbers) add("какие были объёмы или нагрузка")
        }.joinToString(" и ")

        return FollowUpDecision.Ask(
            text = "Уточните, пожалуйста, по предыдущему ответу: $missing? " +
                "Приведите конкретный пример из своего проекта.",
            strongSignals = listOf("Конкретный проект", "Числа", "Личный вклад"),
        )
    }
}
