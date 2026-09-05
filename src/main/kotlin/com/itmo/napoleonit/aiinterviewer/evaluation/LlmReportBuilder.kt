package com.itmo.napoleonit.aiinterviewer.evaluation

import com.itmo.napoleonit.aiinterviewer.config.AppProperties
import com.itmo.napoleonit.aiinterviewer.domain.AnswerState
import com.itmo.napoleonit.aiinterviewer.llm.LlmClient
import com.itmo.napoleonit.aiinterviewer.llm.Schema
import com.itmo.napoleonit.aiinterviewer.llm.Untrusted
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Режим, в котором модель решает всё сама: вердикты по требованиям, балл
 * и рекомендацию.
 *
 * Проще в настройке — правила подкручивать не нужно. Плата за простоту:
 * результат перестаёт быть воспроизводимым. Один и тот же набор ответов может
 * дать разный балл, а объяснить, почему получилось именно столько, нельзя —
 * пересчитать не по чему.
 *
 * Режим выбирается на вакансии и пишется в карточку, чтобы прогоны в разных
 * режимах можно было сравнивать между собой.
 */
@Component
class LlmReportBuilder(
    private val llm: LlmClient,
    private val fallback: RuleBasedReportBuilder,
    private val props: AppProperties,
) : ReportBuilder {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun build(context: ReportContext): Report {
        val requirements = context.requirements
        val numbered = requirements.mapIndexed { i, r ->
            "${i + 1}. [${r.kind}, вес ${r.weight}]${if (r.stopFactor) " [СТОП-ФАКТОР]" else ""}" +
                "${if (r.notVerifiable) " [интервью не проверяет]" else ""} ${r.text}"
        }.joinToString("\n")

        val answers = context.items.map { it.toAnswerReport() }
        val transcript = answers.joinToString("\n\n") { a ->
            buildString {
                append("Вопрос ${a.ord} [${a.kind}]: ${a.questionText}\n")
                append("Статус: ${a.status}")
                a.transcriptRefined?.let { append("\nОтвет: ${Untrusted.strip(it).take(1500)}") }
                a.comment?.let { append("\nЗаметка оценщика: $it") }
            }
        }

        val response = llm.completeJson(
            systemPrompt = SYSTEM,
            userPrompt = """
                Вакансия: ${context.vacancy.title}, грейд ${context.vacancy.grade}.

                Требования:
                $numbered

                Интервью:
                ${Untrusted.block("ИНТЕРВЬЮ", transcript, 40000)}
            """.trimIndent(),
            schemaName = "full_report",
            schema = SCHEMA,
            type = FullReportResponse::class.java,
            reasoningEffort = props.llm.reasoningEffortDeep,
        )

        if (response == null || response.summary.isBlank()) {
            log.warn("Модель не собрала карточку, считаем правилами")
            return fallback.build(context)
        }

        val verdicts = response.requirements.mapNotNull { v ->
            val requirement = requirements.getOrNull(v.requirementNumber - 1) ?: return@mapNotNull null
            RequirementVerdict(
                requirementId = requirement.id,
                text = requirement.text,
                kind = requirement.kind,
                weight = requirement.weight,
                stopFactor = requirement.stopFactor,
                status = enumOrNull<RequirementStatus>(v.status) ?: RequirementStatus.NOT_CHECKED,
                basis = enumOrNull<EvidenceBasis>(v.basis) ?: EvidenceBasis.NONE,
                comment = v.comment,
                // Цитаты берём из сохранённых оценок ответов: выдумать их модель не может
                evidence = answers.find { it.ord == v.answerOrd }?.evidence.orEmpty().take(2),
            )
        }

        return Report(
            interviewId = context.interview.id,
            candidateName = context.interview.candidateName,
            vacancyTitle = context.vacancy.title,
            vacancyGrade = context.vacancy.grade,
            completedAt = context.interview.completedAt ?: Instant.now(),
            recommendation = enumOrNull<Recommendation>(response.recommendation) ?: Recommendation.NEEDS_CHECK,
            overallScore = response.overallScore.coerceIn(0.0, 10.0),
            confidence = enumOrNull<Confidence>(response.confidence) ?: Confidence.LOW,
            summary = response.summary.trim(),
            requirementsMust = verdicts.filter { it.kind == RequirementKind.MUST },
            requirementsNice = verdicts.filter { it.kind == RequirementKind.NICE },
            answers = answers,
            strengths = response.strengths.toFindings(answers),
            risks = response.risks.toFindings(answers),
            skillsFound = response.skillsFound.filter { it.isNotBlank() }.take(20),
            skillsNotChecked = response.skillsNotChecked.filter { it.isNotBlank() }.take(20),
            nextStageQuestions = response.nextStageQuestions.filter { it.isNotBlank() }.take(6),
            candidateFeedback = response.candidateFeedback.trim(),
            technical = TechnicalBlock(
                antifraudEvents = context.antifraud.map { AntifraudEventView(it.type, it.occurredAt) },
                unrateableAnswers = answers.count { it.status == AnswerStatus.UNRATEABLE },
                failedAnswers = answers.count { it.status == AnswerStatus.FAILED },
                notes = listOf(
                    "Оценено ответов: ${answers.count { it.status == AnswerStatus.EVALUATED }} из ${answers.size}",
                    "Вердикты и балл выставила модель целиком. Пересчитать их по правилам нельзя, " +
                        "от прогона к прогону результат может отличаться",
                ),
            ),
            meta = ReportMeta(
                evaluationMode = EvaluationMode.LLM,
                model = llm.modelId(),
                promptVersion = PROMPT_VERSION,
                rubricVersion = "llm",
                questionSetVersion = context.interview.questionSetVersion,
                generatedAt = Instant.now(),
            ),
        )
    }

    private fun ReportItem.toAnswerReport(): AnswerReport {
        val status = answer?.state?.terminal ?: AnswerStatus.FAILED
        return AnswerReport(
            answerId = answer?.id,
            questionId = plan.id,
            ord = plan.ord,
            kind = plan.kind,
            questionText = plan.text,
            requirementId = plan.requirementId,
            origin = plan.origin,
            parentQuestionId = plan.parentQuestionId,
            status = status,
            videoUrl = videoUrl,
            durationMs = answer?.durationMs,
            transcriptRefined = transcript?.refinedText,
            transcriptRaw = transcript?.rawText,
            scores = evaluation?.scores,
            confidence = evaluation?.confidence,
            comment = evaluation?.comment ?: when (status) {
                AnswerStatus.SKIPPED -> "Кандидат пропустил вопрос. Компетенция не проверена."
                AnswerStatus.UNRATEABLE -> "Качество записи не позволяет уверенно оценить ответ."
                AnswerStatus.FAILED -> "Ответ не был обработан из-за технической ошибки."
                AnswerStatus.EVALUATED -> null
            },
            evidence = evaluation?.quotes.orEmpty(),
        )
    }

    private fun List<FindingDto>.toFindings(answers: List<AnswerReport>): List<Finding> =
        filter { it.text.isNotBlank() }.take(6).map { dto ->
            Finding(
                text = dto.text.trim(),
                type = enumOrNull<FindingType>(dto.type) ?: FindingType.INFERENCE,
                evidence = answers.find { it.ord == dto.answerOrd }?.evidence.orEmpty().take(2),
            )
        }

    private inline fun <reified E : Enum<E>> enumOrNull(value: String?): E? =
        value?.let { runCatching { enumValueOf<E>(it) }.getOrNull() }

    data class FullReportResponse(
        val recommendation: String = "NEEDS_CHECK",
        val overallScore: Double = 0.0,
        val confidence: String = "LOW",
        val summary: String = "",
        val requirements: List<RequirementDto> = emptyList(),
        val strengths: List<FindingDto> = emptyList(),
        val risks: List<FindingDto> = emptyList(),
        val skillsFound: List<String> = emptyList(),
        val skillsNotChecked: List<String> = emptyList(),
        val nextStageQuestions: List<String> = emptyList(),
        val candidateFeedback: String = "",
    )

    data class RequirementDto(
        val requirementNumber: Int = 0,
        val status: String = "NOT_CHECKED",
        val basis: String = "NONE",
        val comment: String = "",
        val answerOrd: Int? = null,
    )

    data class FindingDto(
        val text: String = "",
        val type: String = "INFERENCE",
        val answerOrd: Int? = null,
    )

    private companion object {
        const val PROMPT_VERSION = "full-report-v1"

        val FINDING = Schema.obj(
            "text" to Schema.string("Одно утверждение, без воды"),
            "type" to Schema.enumOf("FACT", "INFERENCE", "ASSUMPTION"),
            "answerOrd" to mapOf(
                "type" to listOf("integer", "null"),
                "description" to "Номер вопроса, на ответе к которому основан вывод",
            ),
        )

        val SCHEMA = Schema.obj(
            "recommendation" to Schema.enumOf("FIT", "NOT_FIT", "NEEDS_CHECK"),
            "overallScore" to mapOf("type" to "number", "minimum" to 0, "maximum" to 10),
            "confidence" to Schema.enumOf("LOW", "MEDIUM", "HIGH"),
            "summary" to Schema.string("Абзац: почему получилась именно такая рекомендация"),
            "requirements" to Schema.array(
                Schema.obj(
                    "requirementNumber" to Schema.integer(1, 100),
                    "status" to Schema.enumOf("CONFIRMED", "PARTIAL", "NOT_CONFIRMED", "NOT_CHECKED"),
                    "basis" to Schema.enumOf("ANSWER", "RESUME", "NONE"),
                    "comment" to Schema.string("Коротко, почему такой статус"),
                    "answerOrd" to mapOf(
                        "type" to listOf("integer", "null"),
                        "description" to "Номер вопроса, где это проверялось",
                    ),
                )
            ),
            "strengths" to Schema.array(FINDING),
            "risks" to Schema.array(FINDING),
            "skillsFound" to Schema.array(Schema.string()),
            "skillsNotChecked" to Schema.array(Schema.string()),
            "nextStageQuestions" to Schema.array(Schema.string()),
            "candidateFeedback" to Schema.string("Текст обратной связи лично кандидату"),
        )

        val SYSTEM: String = """
            Ты составляешь заключение по итогам технического видеоинтервью целиком:
            выносишь вердикт по каждому требованию, ставишь общий балл и рекомендацию.

            Правила оценки:
            - по каждому требованию из списка дай ровно один вердикт, ничего не пропускай;
            - обязательные требования весомее желательных, вес указан рядом с каждым;
            - требование с пометкой СТОП-ФАКТОР, если оно не подтверждено, означает
              рекомендацию NOT_FIT независимо от остального;
            - требование с пометкой «интервью не проверяет» всегда NOT_CHECKED;
            - «не проверялось» и «не подтверждено» — разные вещи. Если тему не затрагивали,
              это NOT_CHECKED, а не провал кандидата;
            - отсутствие упоминания навыка не равно отсутствию навыка;
            - если данных мало, честнее NEEDS_CHECK, чем угаданный вердикт;
            - балл от 0 до 10 должен быть согласован с вердиктами: нельзя поставить 9,
              когда половина обязательных требований не подтверждена;
            - внешность, возраст, пол, акцент и манера речи не оцениваются;
            - расшифровка автоматическая: не снижай оценку за оговорки и искажения
              терминов, если смысл понятен.

            Каждое утверждение в strengths и risks помечай типом: FACT — прямо
            прозвучало в ответе, INFERENCE — твой вывод, ASSUMPTION — предположение,
            требующее проверки. Не выдавай предположения за факты.

            Обратная связь кандидату — уважительная и конкретная, без приговоров
            и без обещаний по итогу отбора.

            Итог является рекомендацией: кадровое решение принимает человек.
${Untrusted.RULE}

            Все тексты внутри JSON пиши по-русски.
            Отвечай только JSON по схеме.
        """.trimIndent()
    }
}
