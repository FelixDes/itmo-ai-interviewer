package com.itmo.napoleonit.aiinterviewer.service

import com.itmo.napoleonit.aiinterviewer.config.AppProperties
import com.itmo.napoleonit.aiinterviewer.domain.*
import com.itmo.napoleonit.aiinterviewer.llm.Untrusted
import com.itmo.napoleonit.aiinterviewer.persistence.*
import com.itmo.napoleonit.aiinterviewer.questions.QuestionGenerators
import com.itmo.napoleonit.aiinterviewer.web.*
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class InterviewService(
    private val interviews: InterviewRepository,
    private val answers: AnswerRepository,
    private val vacancies: VacancyRepository,
    private val sets: QuestionSetRepository,
    private val vacancyService: VacancyService,
    private val generators: QuestionGenerators,
    private val processing: AnswerProcessingService,
    private val props: AppProperties,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    @Transactional
    fun create(owner: String, input: InterviewInput): InterviewDetail {
        val vacancy = vacancyService.requireOwned(owner, input.vacancyId)
        if (input.candidateName.isBlank()) {
            throw ValidationFailed(mapOf("candidateName" to "не должно быть пустым"))
        }
        val set = sets.activeSet(vacancy.id)
            ?: throw Conflict("QUESTION_SET_NOT_FROZEN", "У вакансии нет зафиксированного набора вопросов")

        val resume = input.resumeText?.takeIf { it.isNotBlank() }
        val interview = InterviewRow(
            id = UUID.randomUUID(),
            vacancyId = vacancy.id,
            questionSetId = set.id,
            questionSetVersion = set.version,
            candidateName = input.candidateName,
            resumeText = resume,
            status = InterviewStatus.CREATED,
            candidateToken = newToken(),
            expiresAt = Instant.now().plus(props.interview.candidateLinkTtlDays, ChronoUnit.DAYS),
            consentAt = null,
            createdAt = Instant.now(),
            completedAt = null,
            failureStage = null,
            failureMessage = null,
        )
        interviews.insert(interview)

        // Резюме пишет кандидат: если там обращение к модели, рекрутер должен это увидеть
        resume?.let { text ->
            val markers = Untrusted.detectInjection(text)
            if (markers.isNotEmpty()) {
                log.warn("Резюме кандидата {} похоже на попытку внедрения: {}", interview.id, markers)
                interviews.insertAntifraud(
                    AntifraudRow(
                        UUID.randomUUID(), interview.id,
                        AntifraudEventType.PROMPT_INJECTION, Instant.now(),
                    )
                )
            }
        }

        // Ядро снапшотится: карточка обязана показывать то, что видел кандидат (Р-13)
        var ord = 0
        sets.questions(set.id).forEach { q ->
            interviews.insertPlanItem(
                PlanItemRow(
                    id = UUID.randomUUID(), interviewId = interview.id, ord = ++ord,
                    kind = QuestionKind.CORE, origin = q.origin, originQuestionId = q.id,
                    parentQuestionId = null, text = q.text, requirementId = q.requirementId,
                    strongSignals = q.strongSignals, audioKey = null,
                )
            )
        }
        // Персональные вопросы генерятся автоматически, рекрутер их не правит (Р-12)
        if (resume != null) {
            val requirements = vacancies.requirements(vacancy.id)
            generators.of(set.source).generatePersonal(vacancy, requirements, resume).forEach { q ->
                interviews.insertPlanItem(
                    PlanItemRow(
                        id = UUID.randomUUID(), interviewId = interview.id, ord = ++ord,
                        kind = QuestionKind.PERSONAL, origin = q.origin, originQuestionId = null,
                        parentQuestionId = null, text = q.text, requirementId = q.requirementId,
                        strongSignals = q.strongSignals, audioKey = null,
                    )
                )
            }
        }
        return detail(interview)
    }

    fun list(owner: String, vacancyId: UUID?): List<InterviewListItem> {
        vacancyId?.let { vacancyService.requireOwned(owner, it) }
        return interviews.byOwner(owner, vacancyId).map { row ->
            val summary = interviews.reportSummary(row.id)
            InterviewListItem(
                id = row.id,
                candidateName = row.candidateName,
                status = effectiveStatus(row),
                recommendation = summary?.first?.let { Recommendation.valueOf(it) },
                overallScore = summary?.second,
                answered = answeredCount(row.id),
                planned = interviews.plan(row.id).size,
                createdAt = row.createdAt,
                completedAt = row.completedAt,
            )
        }
    }

    fun get(owner: String, id: UUID): InterviewDetail = detail(requireOwned(owner, id))

    fun report(owner: String, id: UUID): Report {
        requireOwned(owner, id)
        return interviews.report(id) ?: throw Conflict("REPORT_NOT_READY", "Карточка ещё не готова")
    }

    fun reanalyze(owner: String, id: UUID): InterviewDetail {
        val row = requireOwned(owner, id)
        if (row.status !in setOf(InterviewStatus.READY_REPORT, InterviewStatus.FAILED)) {
            throw InvalidState("Повторный анализ доступен только для готовой или упавшей карточки")
        }
        interviews.updateStatus(id, InterviewStatus.ANALYZING)
        processing.rebuildReportAsync(id)
        return detail(row.copy(status = InterviewStatus.ANALYZING))
    }

    @Transactional
    fun share(owner: String, id: UUID, ttlDays: Long?): ShareLink {
        requireOwned(owner, id)
        val days = ttlDays ?: props.interview.shareLinkTtlDays
        if (days !in 1..365) throw ValidationFailed(mapOf("ttlDays" to "должен быть от 1 до 365"))
        // Повторная выдача отзывает предыдущую ссылку
        interviews.revokeShares(id, Instant.now())
        val row = ShareLinkRow(
            id = UUID.randomUUID(), interviewId = id, token = newToken(),
            expiresAt = Instant.now().plus(days, ChronoUnit.DAYS), revokedAt = null,
        )
        interviews.insertShare(row)
        return row.toDto()
    }

    fun revokeShare(owner: String, id: UUID) {
        requireOwned(owner, id)
        interviews.revokeShares(id, Instant.now())
    }

    /** Нанимающий менеджер: один отчёт по токену, списка кандидатов у него нет. */
    fun reportByShareToken(token: String): Report {
        val link = interviews.shareByToken(token) ?: throw NotFound("Ссылка не найдена")
        if (link.revokedAt != null) throw Forbidden("LINK_REVOKED", "Ссылка отозвана")
        if (link.expiresAt.isBefore(Instant.now())) throw LinkExpired()
        return interviews.report(link.interviewId) ?: throw Conflict("REPORT_NOT_READY", "Карточка ещё не готова")
    }

    // ---------- вспомогательное ----------

    private fun requireOwned(owner: String, id: UUID): InterviewRow {
        val row = interviews.find(id) ?: throw NotFound("Интервью не найдено")
        vacancyService.requireOwned(owner, row.vacancyId)
        return row
    }

    private fun answeredCount(interviewId: UUID) =
        answers.byInterview(interviewId).count { it.state.settled }

    private fun detail(row: InterviewRow): InterviewDetail {
        val vacancy = vacancies.find(row.vacancyId)!!
        val share = interviews.activeShare(row.id)
        return InterviewDetail(
            id = row.id,
            vacancyId = vacancy.id,
            vacancyTitle = vacancy.title,
            questionSetVersion = row.questionSetVersion,
            candidateName = row.candidateName,
            resumeText = row.resumeText,
            status = effectiveStatus(row),
            candidateUrl = "${props.publicBaseUrl}/s/${row.candidateToken}",
            expiresAt = row.expiresAt,
            consentAt = row.consentAt,
            answered = answeredCount(row.id),
            planned = interviews.plan(row.id).size,
            share = share?.toDto(),
            reportAvailable = interviews.reportSummary(row.id) != null,
            failure = row.failureStage?.let { InterviewFailure(it, row.failureMessage ?: "") },
            createdAt = row.createdAt,
            completedAt = row.completedAt,
        )
    }

    private fun ShareLinkRow.toDto() = ShareLink(
        url = "${props.publicBaseUrl}/r/$token",
        token = token,
        expiresAt = expiresAt,
        revoked = revokedAt != null,
    )
}

/** Истечение ссылки считается на лету, отдельного планировщика нет. */
fun effectiveStatus(row: InterviewRow): InterviewStatus =
    if (row.status in setOf(InterviewStatus.CREATED, InterviewStatus.READY, InterviewStatus.IN_PROGRESS) &&
        row.expiresAt.isBefore(Instant.now())
    ) InterviewStatus.EXPIRED else row.status

fun newToken(): String = UUID.randomUUID().toString().replace("-", "")
