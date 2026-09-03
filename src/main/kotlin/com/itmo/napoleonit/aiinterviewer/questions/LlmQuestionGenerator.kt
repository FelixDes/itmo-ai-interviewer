package com.itmo.napoleonit.aiinterviewer.questions

import com.itmo.napoleonit.aiinterviewer.domain.RequirementRow
import com.itmo.napoleonit.aiinterviewer.domain.VacancyRow
import com.itmo.napoleonit.aiinterviewer.llm.LlmClient
import com.itmo.napoleonit.aiinterviewer.llm.Schema
import com.itmo.napoleonit.aiinterviewer.web.dto.QuestionOrigin
import com.itmo.napoleonit.aiinterviewer.web.dto.QuestionSetSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Генерация вопросов моделью. При недоступности LLM откатывается на эталонный
 * набор: рекрутер получит рабочие вопросы в любом случае.
 */
@Component
class LlmQuestionGenerator(
    private val llm: LlmClient,
    private val fallback: ReferenceQuestionGenerator,
) : QuestionGenerator {

    private val log = LoggerFactory.getLogger(javaClass)

    override val source = QuestionSetSource.LLM

    override fun generateCore(vacancy: VacancyRow, requirements: List<RequirementRow>): List<GeneratedQuestion> {
        val numbered = requirements.mapIndexed { i, r ->
            "${i + 1}. [${r.kind}, вес ${r.weight}] ${r.text}" + if (r.notVerifiable) " (интервью не проверяет)" else ""
        }.joinToString("\n")

        val response = llm.completeJson(
            systemPrompt = CORE_SYSTEM,
            userPrompt = """
                Вакансия: ${vacancy.title} (грейд ${vacancy.grade}).
                Описание: ${vacancy.description}

                Требования:
                $numbered

                Составь 5–7 вопросов для асинхронного видеоинтервью.
            """.trimIndent(),
            schemaName = "core_questions",
            schema = QUESTIONS_SCHEMA,
            type = QuestionsResponse::class.java,
        )

        val questions = response?.questions.orEmpty()
            .filter { it.text.isNotBlank() }
            .map { it.toGenerated(requirements, QuestionOrigin.VACANCY) }
            .take(7)

        if (questions.size < 3) {
            log.warn("LLM вернула {} вопросов, берём эталонный набор", questions.size)
            return fallback.generateCore(vacancy, requirements)
        }
        return questions
    }

    override fun generatePersonal(
        vacancy: VacancyRow,
        requirements: List<RequirementRow>,
        resumeText: String,
    ): List<GeneratedQuestion> {
        val response = llm.completeJson(
            systemPrompt = PERSONAL_SYSTEM,
            userPrompt = """
                Вакансия: ${vacancy.title} (грейд ${vacancy.grade}).

                Резюме кандидата:
                ${resumeText.take(8000)}

                Составь 2–3 персональных вопроса.
            """.trimIndent(),
            schemaName = "personal_questions",
            schema = QUESTIONS_SCHEMA,
            type = QuestionsResponse::class.java,
        )

        val questions = response?.questions.orEmpty()
            .filter { it.text.isNotBlank() }
            .map { it.toGenerated(requirements, QuestionOrigin.RESUME) }
            .take(3)

        return questions.ifEmpty { fallback.generatePersonal(vacancy, requirements, resumeText) }
    }

    private fun GeneratedQuestionDto.toGenerated(
        requirements: List<RequirementRow>,
        origin: QuestionOrigin,
    ) = GeneratedQuestion(
        text = text.trim(),
        // Модель отдаёт номер из списка, а не UUID: так она не может выдумать идентификатор
        requirementId = requirementNumber?.let { requirements.getOrNull(it - 1)?.id },
        strongSignals = strongSignals.filter { it.isNotBlank() }.take(5),
        origin = origin,
    )

    data class QuestionsResponse(val questions: List<GeneratedQuestionDto> = emptyList())

    data class GeneratedQuestionDto(
        val text: String = "",
        val requirementNumber: Int? = null,
        val strongSignals: List<String> = emptyList(),
    )

    private companion object {
        val QUESTIONS_SCHEMA = Schema.obj(
            "questions" to Schema.array(
                Schema.obj(
                    "text" to Schema.string("Текст вопроса, обращение к кандидату на «вы»"),
                    "requirementNumber" to mapOf(
                        "type" to listOf("integer", "null"),
                        "description" to "Номер проверяемого требования из списка, либо null",
                    ),
                    "strongSignals" to Schema.array(
                        Schema.string("Признак сильного ответа, короткой фразой")
                    ),
                )
            )
        )

        val CORE_SYSTEM = """
            Ты помогаешь техническому рекрутеру составить вопросы для асинхронного видеоинтервью.
            Кандидат отвечает голосом на камеру, без собеседника и без возможности переспросить.

            Правила:
            - вопрос должен проверять конкретное требование вакансии;
            - спрашивай про практический опыт, а не определения: «расскажите, как вы делали», а не «что такое»;
            - формулируй так, чтобы закрытым «да/нет» ответить было нельзя;
            - проси конкретику: пример из проекта, личный вклад, масштаб и результат;
            - один вопрос — одна тема, без длинных многосоставных конструкций;
            - не спрашивай то, что нельзя проверить голосовым ответом (написание кода, работа в IDE);
            - не задавай вопросов про возраст, семью, гражданство и прочее, не относящееся к работе.

            Для каждого вопроса перечисли признаки сильного ответа: что должно прозвучать,
            чтобы считать компетенцию подтверждённой.

            Отвечай только JSON по схеме.
        """.trimIndent()

        val PERSONAL_SYSTEM = """
            Ты составляешь персональные вопросы к кандидату по его резюме для видеоинтервью.

            Правила:
            - проверяй заявленный в резюме опыт: масштаб задач, личный вклад, конкретные решения;
            - не уходи в темы, не относящиеся к вакансии;
            - считай написанное в резюме утверждением кандидата, а не фактом: вопрос должен помогать это проверить;
            - если в резюме есть противоречие или подозрительно широкий стек — спроси об этом прямо, но нейтрально;
            - никаких вопросов о личной жизни, возрасте, здоровье и прочем, не относящемся к работе.

            Отвечай только JSON по схеме.
        """.trimIndent()
    }
}
