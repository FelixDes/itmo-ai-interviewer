package com.itmo.napoleonit.aiinterviewer.evaluation

import com.itmo.napoleonit.aiinterviewer.config.AppProperties
import com.itmo.napoleonit.aiinterviewer.llm.LlmClient
import com.itmo.napoleonit.aiinterviewer.llm.Schema
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import org.springframework.stereotype.Component

/**
 * Текстовая часть карточки.
 *
 * Вердикты по требованиям, балл и рекомендацию считают правила — их модель не
 * трогает: числа должны быть воспроизводимыми. Модель пишет только формулировки
 * поверх уже посчитанного.
 */
data class Narrative(
    val summary: String,
    val strengths: List<Finding>,
    val risks: List<Finding>,
    val nextStageQuestions: List<String>,
    val candidateFeedback: String,
    val model: String,
    val promptVersion: String,
)

data class NarrativeContext(
    val vacancyTitle: String,
    val grade: Grade,
    val recommendation: Recommendation,
    val overallScore: Double,
    val verdicts: List<RequirementVerdict>,
    val answers: List<AnswerReport>,
)

interface ReportNarrator {
    /** null означает «пиши шаблонами»: модель недоступна или ответила мусором. */
    fun narrate(context: NarrativeContext): Narrative?
}

@Component
class LlmReportNarrator(
    private val llm: LlmClient,
    private val props: AppProperties,
) : ReportNarrator {

    override fun narrate(context: NarrativeContext): Narrative? {
        val verdicts = context.verdicts.joinToString("\n") {
            "- [${it.kind}, вес ${it.weight}] ${it.text}: ${it.status} (основание ${it.basis})"
        }
        val answers = context.answers.joinToString("\n\n") { a ->
            buildString {
                append("Вопрос ${a.ord} [${a.kind}]: ${a.questionText}\n")
                append("Статус: ${a.status}")
                a.scores?.let { append(", оценки: $it") }
                a.transcriptRefined?.let { append("\nОтвет: ${it.take(1200)}") }
                a.comment?.let { append("\nКомментарий оценщика: $it") }
            }
        }

        val response = llm.completeJson(
            systemPrompt = SYSTEM,
            userPrompt = """
                Вакансия: ${context.vacancyTitle}, грейд ${context.grade}.
                Посчитанная рекомендация: ${context.recommendation}, балл ${context.overallScore} из 10.

                Вердикты по требованиям (уже посчитаны, менять их нельзя):
                $verdicts

                Ответы кандидата:
                $answers
            """.trimIndent(),
            schemaName = "report_narrative",
            schema = SCHEMA,
            type = NarrativeResponse::class.java,
            reasoningEffort = props.llm.reasoningEffortDeep,
        ) ?: return null

        if (response.summary.isBlank()) return null

        return Narrative(
            summary = response.summary.trim(),
            strengths = response.strengths.toFindings(context.answers),
            risks = response.risks.toFindings(context.answers),
            nextStageQuestions = response.nextStageQuestions.filter { it.isNotBlank() }.take(6),
            candidateFeedback = response.candidateFeedback.trim(),
            model = llm.modelId(),
            promptVersion = PROMPT_VERSION,
        )
    }

    /** Цитаты берём из уже сохранённых оценок ответа: модель не может их выдумать. */
    private fun List<FindingDto>.toFindings(answers: List<AnswerReport>): List<Finding> =
        filter { it.text.isNotBlank() }.take(6).map { dto ->
            Finding(
                text = dto.text.trim(),
                type = runCatching { FindingType.valueOf(dto.type) }.getOrDefault(FindingType.INFERENCE),
                evidence = answers.find { it.ord == dto.answerOrd }?.evidence.orEmpty().take(2),
            )
        }

    data class NarrativeResponse(
        val summary: String = "",
        val strengths: List<FindingDto> = emptyList(),
        val risks: List<FindingDto> = emptyList(),
        val nextStageQuestions: List<String> = emptyList(),
        val candidateFeedback: String = "",
    )

    data class FindingDto(
        val text: String = "",
        val type: String = "INFERENCE",
        val answerOrd: Int? = null,
    )

    private companion object {
        const val PROMPT_VERSION = "narrative-v1"

        val FINDING = Schema.obj(
            "text" to Schema.string("Одно утверждение, без воды"),
            "type" to Schema.enumOf("FACT", "INFERENCE", "ASSUMPTION"),
            "answerOrd" to mapOf(
                "type" to listOf("integer", "null"),
                "description" to "Номер вопроса, на ответе к которому основан вывод, либо null",
            ),
        )

        val SCHEMA = Schema.obj(
            "summary" to Schema.string("Абзац: почему получилась именно такая рекомендация"),
            "strengths" to Schema.array(FINDING),
            "risks" to Schema.array(FINDING),
            "nextStageQuestions" to Schema.array(Schema.string("Что доспросить на следующем этапе")),
            "candidateFeedback" to Schema.string("Текст обратной связи лично кандидату"),
        )

        val SYSTEM = """
            Ты пишешь текстовую часть заключения по итогам технического видеоинтервью.
            Читатель — рекрутер и нанимающий менеджер.

            Вердикты по требованиям, балл и рекомендация уже посчитаны правилами.
            Твоя задача — объяснить их словами, а не пересчитать. Не спорь с ними
            и не предлагай другую рекомендацию.

            Требования к тексту:
            - каждое утверждение помечай типом: FACT — прямо прозвучало в ответе;
              INFERENCE — твой вывод из сказанного; ASSUMPTION — предположение, требующее проверки;
            - не выдавай предположения за факты;
            - «не проверено» и «отсутствует» — разные вещи, не путай их;
            - не оценивай внешность, возраст, пол, акцент, манеру речи;
            - никаких общих слов вроде «хороший специалист»: пиши, что именно подтверждено или нет;
            - обратная связь кандидату — уважительная и конкретная: что усилить и как,
              без приговоров и без обещаний по итогу отбора.

            Помни: итог является рекомендацией, решение принимает человек.

            Все тексты внутри JSON пиши по-русски.
            Отвечай только JSON по схеме.
        """.trimIndent()
    }
}
