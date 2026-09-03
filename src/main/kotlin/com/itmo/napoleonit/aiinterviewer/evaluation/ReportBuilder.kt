package com.itmo.napoleonit.aiinterviewer.evaluation

import com.itmo.napoleonit.aiinterviewer.domain.*
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import org.springframework.stereotype.Component
import java.time.Instant

/** Один вопрос плана со всем, что к нему привязано. */
data class ReportItem(
    val plan: PlanItemRow,
    val answer: AnswerRow?,
    val transcript: TranscriptRow?,
    val evaluation: EvaluationRow?,
    val videoUrl: String?,
)

data class ReportContext(
    val interview: InterviewRow,
    val vacancy: VacancyRow,
    val requirements: List<RequirementRow>,
    val items: List<ReportItem>,
    val antifraud: List<AntifraudRow>,
)

interface ReportBuilder {
    fun build(context: ReportContext): Report
}

/**
 * Сборка карточки по правилам Рамки §8.
 *
 * Логика настоящая: вердикты выводятся из оценок ответов, обязательные и
 * желательные требования считаются раздельно, непроверенное не путается с
 * отсутствующим, каждый существенный вывод подпирается цитатой. Заглушечная
 * часть — только текст резюмирующих формулировок, его позже напишет LLM.
 */
@Component
class RuleBasedReportBuilder(private val narrator: ReportNarrator) : ReportBuilder {

    override fun build(context: ReportContext): Report {
        val answers = context.items.map { it.toAnswerReport() }
        val verdicts = context.requirements.map { verdict(it, context) }

        val mustVerdicts = verdicts.filter { it.kind == RequirementKind.MUST }
        val score = overallScore(mustVerdicts)
        val recommendation = recommend(mustVerdicts, score)

        // Числа считают правила, формулировки пишет модель. Недоступна — берём шаблоны.
        val narrative = runCatching {
            narrator.narrate(
                NarrativeContext(
                    vacancyTitle = context.vacancy.title,
                    grade = context.vacancy.grade,
                    recommendation = recommendation,
                    overallScore = score,
                    verdicts = verdicts,
                    answers = answers,
                )
            )
        }.getOrNull()

        return Report(
            interviewId = context.interview.id,
            candidateName = context.interview.candidateName,
            vacancyTitle = context.vacancy.title,
            vacancyGrade = context.vacancy.grade,
            completedAt = context.interview.completedAt ?: Instant.now(),
            recommendation = recommendation,
            overallScore = score,
            confidence = confidence(answers),
            summary = narrative?.summary ?: summary(recommendation, mustVerdicts, answers),
            requirementsMust = mustVerdicts,
            requirementsNice = verdicts.filter { it.kind == RequirementKind.NICE },
            answers = answers,
            strengths = narrative?.strengths?.ifEmpty { null } ?: strengths(answers, verdicts),
            risks = narrative?.risks?.ifEmpty { null } ?: risks(answers, verdicts),
            skillsFound = verdicts
                .filter { it.status == RequirementStatus.CONFIRMED || it.status == RequirementStatus.PARTIAL }
                .map { it.text.shortLabel() },
            skillsNotChecked = verdicts
                .filter { it.status == RequirementStatus.NOT_CHECKED }
                .map { it.text.shortLabel() },
            nextStageQuestions = narrative?.nextStageQuestions?.ifEmpty { null }
                ?: nextStageQuestions(verdicts, answers),
            candidateFeedback = narrative?.candidateFeedback?.ifBlank { null } ?: candidateFeedback(answers),
            technical = TechnicalBlock(
                antifraudEvents = context.antifraud.map { AntifraudEventView(it.type, it.occurredAt) },
                unrateableAnswers = answers.count { it.status == AnswerStatus.UNRATEABLE },
                failedAnswers = answers.count { it.status == AnswerStatus.FAILED },
                notes = buildList {
                    add("Оценено ответов: ${answers.count { it.status == AnswerStatus.EVALUATED }} из ${answers.size}")
                    val skipped = answers.count { it.status == AnswerStatus.SKIPPED }
                    if (skipped > 0) add("Кандидат пропустил вопросов: $skipped")
                    if (narrative == null) {
                        add("Модель недоступна: формулировки собраны шаблонами, оценки — правилами")
                    }
                },
            ),
            meta = ReportMeta(
                model = narrative?.model ?: "rule-based",
                promptVersion = narrative?.promptVersion ?: "rule-based",
                rubricVersion = "v1",
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

    private fun verdict(requirement: RequirementRow, context: ReportContext): RequirementVerdict {
        val related = context.items.filter { it.plan.requirementId == requirement.id }
        val evaluated = related.filter { it.answer?.state == AnswerState.EVALUATED && it.evaluation != null }
        val mentionedInResume = context.interview.resumeText
            ?.contains(requirement.text.shortLabel(), ignoreCase = true) == true

        // Рамка §8: отсутствие упоминания навыка не равно отсутствию навыка
        val status = when {
            requirement.notVerifiable -> RequirementStatus.NOT_CHECKED
            evaluated.isEmpty() && mentionedInResume -> RequirementStatus.NOT_CONFIRMED
            evaluated.isEmpty() -> RequirementStatus.NOT_CHECKED
            else -> when (evaluated.mapNotNull { it.evaluation?.scores?.average() }.average()) {
                in 3.5..5.0 -> RequirementStatus.CONFIRMED
                in 2.0..3.5 -> RequirementStatus.PARTIAL
                else -> RequirementStatus.NOT_CONFIRMED
            }
        }

        val basis = when {
            status == RequirementStatus.NOT_CONFIRMED && evaluated.isEmpty() -> EvidenceBasis.RESUME
            evaluated.isNotEmpty() -> EvidenceBasis.ANSWER
            else -> EvidenceBasis.NONE
        }

        return RequirementVerdict(
            requirementId = requirement.id,
            text = requirement.text,
            kind = requirement.kind,
            weight = requirement.weight,
            stopFactor = requirement.stopFactor,
            status = status,
            basis = basis,
            comment = when {
                requirement.notVerifiable -> "Этим интервью не проверяется."
                status == RequirementStatus.CONFIRMED -> "Подтверждено ответом кандидата."
                status == RequirementStatus.PARTIAL -> "Упомянуто, но без глубины или без личного вклада."
                basis == EvidenceBasis.RESUME -> "Заявлено в резюме, ответом не подтверждено."
                else -> "В интервью не затрагивалось."
            },
            evidence = evaluated.flatMap { it.evaluation?.quotes.orEmpty() }.take(2),
        )
    }

    private fun overallScore(must: List<RequirementVerdict>): Double {
        val scored = must.filter { it.status != RequirementStatus.NOT_CHECKED }
        if (scored.isEmpty()) return 0.0
        val total = scored.sumOf { it.weight.toDouble() }
        val earned = scored.sumOf {
            it.weight * when (it.status) {
                RequirementStatus.CONFIRMED -> 1.0
                RequirementStatus.PARTIAL -> 0.5
                else -> 0.0
            }
        }
        return Math.round(earned / total * 100.0) / 10.0
    }

    private fun recommend(must: List<RequirementVerdict>, score: Double): Recommendation {
        val stopFactorFailed = must.any { it.stopFactor && it.status == RequirementStatus.NOT_CONFIRMED }
        val unchecked = must.count { it.status == RequirementStatus.NOT_CHECKED }
        return when {
            stopFactorFailed -> Recommendation.NOT_FIT
            // Слишком много непроверенного — это не «не подходит», а «надо доспросить»
            unchecked > must.size / 2 -> Recommendation.NEEDS_CHECK
            score >= 7.0 -> Recommendation.FIT
            score < 4.0 -> Recommendation.NOT_FIT
            else -> Recommendation.NEEDS_CHECK
        }
    }

    private fun confidence(answers: List<AnswerReport>): Confidence {
        val evaluated = answers.count { it.status == AnswerStatus.EVALUATED }
        return when {
            answers.isEmpty() -> Confidence.LOW
            evaluated >= answers.size * 0.8 -> Confidence.HIGH
            evaluated >= answers.size * 0.4 -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
    }

    private fun summary(
        recommendation: Recommendation,
        must: List<RequirementVerdict>,
        answers: List<AnswerReport>,
    ): String {
        val confirmed = must.count { it.status == RequirementStatus.CONFIRMED }
        val unchecked = must.count { it.status == RequirementStatus.NOT_CHECKED }
        val skipped = answers.count { it.status == AnswerStatus.SKIPPED }
        val head = when (recommendation) {
            Recommendation.FIT -> "Кандидат подтвердил большинство обязательных требований вакансии."
            Recommendation.NOT_FIT -> "Кандидат не подтвердил ключевые обязательные требования вакансии."
            Recommendation.NEEDS_CHECK -> "Данных интервью недостаточно для однозначного решения."
        }
        return buildString {
            append(head)
            append(" Подтверждено обязательных требований: $confirmed из ${must.size}")
            if (unchecked > 0) append(", не проверено: $unchecked")
            append(".")
            if (skipped > 0) append(" Кандидат пропустил $skipped вопрос(ов), эти компетенции остались непроверенными.")
            append(" Итог является рекомендацией: кадровое решение остаётся за человеком.")
        }
    }

    private fun strengths(answers: List<AnswerReport>, verdicts: List<RequirementVerdict>): List<Finding> {
        val confirmed = verdicts.filter { it.status == RequirementStatus.CONFIRMED }.take(3).map {
            Finding("Подтверждено требование: ${it.text}", FindingType.FACT, it.evidence)
        }
        val strong = answers
            .filter { (it.scores?.average() ?: 0.0) >= 3.5 }
            .take(2)
            .map { Finding("Уверенный ответ на вопрос ${it.ord}: ${it.questionText.take(80)}…", FindingType.FACT, it.evidence) }
        return (confirmed + strong).ifEmpty {
            listOf(Finding("Сильные стороны по итогам интервью выделить не удалось.", FindingType.INFERENCE))
        }
    }

    private fun risks(answers: List<AnswerReport>, verdicts: List<RequirementVerdict>): List<Finding> = buildList {
        verdicts.filter { it.status == RequirementStatus.NOT_CONFIRMED && it.basis == EvidenceBasis.RESUME }
            .take(2)
            .forEach { add(Finding("Заявлено в резюме, но не подтверждено ответом: ${it.text}", FindingType.FACT)) }

        val noContribution = answers.count { (it.scores?.personalContribution ?: 5) <= 2 }
        if (noContribution > 0) {
            add(Finding("В $noContribution ответе(ах) не раскрыт личный вклад кандидата.", FindingType.FACT))
        }
        val noMetrics = answers.count { (it.scores?.scaleAndMetrics ?: 5) <= 2 }
        if (noMetrics > 0) {
            add(Finding("В $noMetrics ответе(ах) нет масштаба, результата или метрик.", FindingType.FACT))
        }
        val skipped = answers.filter { it.status == AnswerStatus.SKIPPED }
        if (skipped.isNotEmpty()) {
            add(Finding("Пропущено вопросов: ${skipped.size}. Возможен пробел в этих темах.", FindingType.ASSUMPTION))
        }
        if (isEmpty()) add(Finding("Существенных рисков по итогам интервью не выявлено.", FindingType.INFERENCE))
    }

    private fun nextStageQuestions(verdicts: List<RequirementVerdict>, answers: List<AnswerReport>): List<String> =
        buildList {
            verdicts.filter { it.status == RequirementStatus.NOT_CHECKED && !it.stopFactor }
                .take(3)
                .forEach { add("Проверить на следующем этапе: ${it.text}") }
            answers.filter { (it.scores?.personalContribution ?: 5) <= 2 }
                .take(2)
                .forEach { add("Уточнить личный вклад по вопросу ${it.ord}: ${it.questionText.take(70)}…") }
        }.ifEmpty { listOf("Дополнительных вопросов по итогам интервью не требуется.") }

    private fun candidateFeedback(answers: List<AnswerReport>): String {
        val gaps = buildList {
            if (answers.any { (it.scores?.example ?: 5) <= 2 }) add("больше конкретных примеров из своих проектов")
            if (answers.any { (it.scores?.personalContribution ?: 5) <= 2 }) add("яснее описывать свой личный вклад")
            if (answers.any { (it.scores?.scaleAndMetrics ?: 5) <= 2 }) add("приводить цифры: объёмы, нагрузку, результат")
        }
        return buildString {
            append("Спасибо за участие в интервью. По ответам видно техническую базу и практический опыт.\n\n")
            if (gaps.isEmpty()) {
                append("Ответы структурные и подкреплены примерами — так держать.")
            } else {
                append("Что стоит усилить в следующий раз: ")
                append(gaps.joinToString("; "))
                append(".\n\nЭто типичная зона роста: техническая экспертиза считывается лучше, ")
                append("когда за каждым утверждением стоит конкретный случай из вашей практики.")
            }
        }
    }
}

private fun Scores.average(): Double =
    listOfNotNull(technicalCorrectness, depth, relevance, example, personalContribution, scaleAndMetrics)
        .map { it.toDouble() }
        .ifEmpty { listOf(0.0) }
        .average()

/** «PostgreSQL — проектирование схем» -> «PostgreSQL» */
private fun String.shortLabel(): String = substringBefore(" —").substringBefore(" (").trim()
