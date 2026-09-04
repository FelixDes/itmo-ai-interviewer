package com.itmo.napoleonit.aiinterviewer.questions

import com.itmo.napoleonit.aiinterviewer.llm.LlmClient
import com.itmo.napoleonit.aiinterviewer.llm.Schema
import com.itmo.napoleonit.aiinterviewer.llm.Untrusted
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * Решение об уточняющем вопросе моделью (Р-1).
 *
 * Лимиты проверяются до обращения к LLM: длительность интервью должна оставаться
 * предсказуемой независимо от того, что решит модель (Рамка §5).
 * Если модель недоступна — работает правило, интервью не встаёт.
 */
@Primary
@Component
class LlmFollowUpGenerator(
    private val llm: LlmClient,
    private val fallback: RuleBasedFollowUpGenerator,
) : FollowUpGenerator {

    override fun decide(context: FollowUpContext): FollowUpDecision {
        if (context.followUpsForThisAnswer >= context.maxFollowUpsPerAnswer) return FollowUpDecision.Proceed
        if (context.totalQuestions >= context.maxTotalQuestions) return FollowUpDecision.Proceed

        val response = llm.completeJson(
            systemPrompt = SYSTEM,
            userPrompt = """
                Вопрос: ${context.questionText}

                Признаки сильного ответа:
                ${context.strongSignals.joinToString("\n") { "- $it" }}

                Ответ кандидата (расшифровка):
                ${Untrusted.block("ОТВЕТ", context.transcript, 6000)}
            """.trimIndent(),
            schemaName = "follow_up_decision",
            schema = SCHEMA,
            type = FollowUpResponse::class.java,
        ) ?: return fallback.decide(context)

        if (!response.ask || response.question.isNullOrBlank()) return FollowUpDecision.Proceed

        return FollowUpDecision.Ask(
            text = response.question.trim(),
            strongSignals = response.strongSignals.filter { it.isNotBlank() }.take(5),
        )
    }

    data class FollowUpResponse(
        val ask: Boolean = false,
        val reason: String = "",
        val question: String? = null,
        val strongSignals: List<String> = emptyList(),
    )

    private companion object {
        val SCHEMA = Schema.obj(
            "ask" to Schema.bool(),
            "reason" to Schema.string("Коротко: чего не хватило в ответе, либо почему уточнение не нужно"),
            "question" to mapOf(
                "type" to listOf("string", "null"),
                "description" to "Текст уточняющего вопроса, либо null если ask = false",
            ),
            "strongSignals" to Schema.array(Schema.string()),
        )

        val SYSTEM: String = """
            Ты ведёшь техническое интервью и решаешь, нужен ли один уточняющий вопрос.

            Уточняй, если ответ:
            - слишком общий и без конкретного примера;
            - не раскрывает личный вклад кандидата (описана работа команды, а не его);
            - не содержит масштаба, результата или метрик;
            - противоречит сказанному ранее;
            - требует проверки технической глубины: механизм назван, но не объяснён.

            Не уточняй, если ответ уже полный, либо если кандидат явно не работал с темой:
            переспрашивать то, чего человек не знает, бессмысленно и неприятно.

            Ограничения:
            - уточнение остаётся в рамках той же компетенции, новую тему не открываем;
            - ровно один вопрос, коротко, на «вы»;
            - не переспрашивай то, что кандидат уже сказал.
${Untrusted.RULE}

            Все тексты внутри JSON пиши по-русски.
            Отвечай только JSON по схеме.
        """.trimIndent()
    }
}
