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
                ${askedBlock(context)}
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

    /** Показываем модели уже заданные уточнения, иначе она спрашивает одно и то же. */
    private fun askedBlock(context: FollowUpContext): String =
        if (context.alreadyAsked.isEmpty()) ""
        else "\nВы уже спрашивали в этом интервью:\n" +
            context.alreadyAsked.joinToString("\n") { "- $it" }

    data class FollowUpResponse(
        val ask: Boolean = false,
        val reason: String = "",
        val question: String? = null,
        val strongSignals: List<String> = emptyList(),
    )

    private companion object {
        val SCHEMA = Schema.obj(
            "ask" to Schema.bool(),
            "reason" to Schema.string("Что именно изменится в оценке, если кандидат ответит. Не можешь назвать — ask = false"),
            "question" to mapOf(
                "type" to listOf("string", "null"),
                "description" to "Текст уточняющего вопроса, либо null если ask = false",
            ),
            "strongSignals" to Schema.array(Schema.string()),
        )

        val SYSTEM: String = """
            Ты ведёшь техническое интервью. Реши, задать ли один уточняющий вопрос.

            По умолчанию — не задавать. Уточнение нужно доказать, а не придумать.
            Лишний вопрос отнимает время у кандидата и не добавляет данных для решения,
            поэтому промолчать почти всегда лучше, чем спросить слабое.

            Задавай вопрос, только если выполнены сразу три условия:
            1. Кандидат не говорил, что не работал с темой. Он отвечает по существу,
               пусть и общими словами, профессиональным языком.
            2. Не хватает ровно одного проверяемого факта: числа, названия инструмента,
               собственного решения кандидата, результата.
            3. Получив этот факт, ты изменишь оценку компетенции. Если ответ и так
               понятен — подтверждён или явно провален — вопрос бесполезен.

            Типичный случай, когда спросить нужно: кандидат верно называет подход, но не
            привёл ни одного случая из своей практики. Тогда попроси один конкретный случай.

            Никогда не спрашивай:
            - о гипотетическом: «что бы вы сделали», «где хотели бы применить»,
              «какой результат ожидали бы». Опыта это не проверяет;
            - о том, чего кандидат не делал. Он уже сказал, что не работал с этим,
              и переспрашивать унизительно и бессмысленно;
            - пересказ исходного вопроса другими словами;
            - то, что уже спрашивали. Если кандидат не дал факт с первого раза, он его
              не даст и со второго: считай компетенцию неподтверждённой и иди дальше;
            - сразу о нескольких вещах;
            - про мотивацию, планы и предпочтения: это не техническая компетенция.

            Если решил спросить — спрашивай о конкретном факте из прошлого кандидата,
            одним коротким предложением на «вы». Хороший уточняющий вопрос звучит как
            «Какой объём данных там был?» или «Что именно из этого делали вы?», а не как
            рассуждение на три строки.

            В поле reason сначала честно напиши, что именно изменится в оценке, если
            кандидат ответит. Не можешь назвать — значит ask = false.
${Untrusted.RULE}

            Все тексты внутри JSON пиши по-русски.
            Отвечай только JSON по схеме.
        """.trimIndent()
    }
}
