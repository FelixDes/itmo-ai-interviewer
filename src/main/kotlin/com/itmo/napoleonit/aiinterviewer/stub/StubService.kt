package com.itmo.napoleonit.aiinterviewer.stub

import com.itmo.napoleonit.aiinterviewer.config.AppProperties
import com.itmo.napoleonit.aiinterviewer.media.S3Service
import com.itmo.napoleonit.aiinterviewer.web.*
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class StubService(
    private val store: StubStore,
    private val pipeline: StubPipeline,
    private val s3: S3Service,
    private val props: AppProperties,
) {
    private val companyName = "Napoleon IT"

    // ---------- вакансии ----------

    fun listVacancies(owner: String): List<VacancyListItem> =
        store.vacancies.values
            .filter { store.vacancyOwner[it.id] == owner }
            .sortedByDescending { it.createdAt }
            .map { v ->
                VacancyListItem(
                    id = v.id,
                    title = v.title,
                    grade = v.grade,
                    interviewCount = store.interviewsOf(v.id).size,
                    hasFrozenQuestionSet = store.activeSet(v.id) != null,
                    createdAt = v.createdAt,
                )
            }

    fun createVacancy(owner: String, input: VacancyInput): Vacancy {
        validate(input)
        val id = UUID.randomUUID()
        val vacancy = Vacancy(
            id = id,
            title = input.title,
            grade = input.grade,
            description = input.description,
            requirements = input.requirements.map { it.toRequirement(UUID.randomUUID()) },
            activeQuestionSet = null,
            draftQuestionSet = null,
            createdAt = Instant.now(),
        )
        store.vacancies[id] = vacancy
        store.vacancyOwner[id] = owner
        return vacancy
    }

    fun getVacancy(owner: String, id: UUID): Vacancy = withRefs(requireOwned(owner, id))

    fun updateVacancy(owner: String, id: UUID, input: VacancyInput): Vacancy {
        validate(input)
        val existing = requireOwned(owner, id)
        // id сохраняется -> обновляем; без id -> создаём. Удалённые просто выпадают.
        val updated = existing.copy(
            title = input.title,
            grade = input.grade,
            description = input.description,
            requirements = input.requirements.map { it.toRequirement(it.id ?: UUID.randomUUID()) },
        )
        store.vacancies[id] = updated
        return withRefs(updated)
    }

    private fun requireOwned(owner: String, id: UUID): Vacancy {
        val vacancy = store.vacancy(id)
        if (store.vacancyOwner[id] != owner) throw Forbidden(message = "Вакансия другого рекрутера")
        return vacancy
    }

    private fun withRefs(v: Vacancy): Vacancy = v.copy(
        activeQuestionSet = store.activeSet(v.id)?.toRef(),
        draftQuestionSet = store.draftSet(v.id)?.toRef(),
    )

    private fun validate(input: VacancyInput) {
        val errors = buildMap {
            if (input.title.isBlank()) put("title", "не должно быть пустым")
            if (input.requirements.isEmpty()) put("requirements", "нужно хотя бы одно требование")
            input.requirements.forEachIndexed { i, r ->
                if (r.text.isBlank()) put("requirements[$i].text", "не должно быть пустым")
                if (r.weight !in 1..3) put("requirements[$i].weight", "должен быть от 1 до 3")
            }
        }
        if (errors.isNotEmpty()) throw ValidationFailed(errors)
    }

    // ---------- наборы вопросов ----------

    fun listQuestionSets(owner: String, vacancyId: UUID): List<QuestionSet> {
        requireOwned(owner, vacancyId)
        return store.setsOf(vacancyId)
    }

    fun generateQuestionSet(owner: String, vacancyId: UUID, source: QuestionSetSource): QuestionSet {
        val vacancy = requireOwned(owner, vacancyId)
        val nextVersion = (store.setsOf(vacancyId).maxOfOrNull { it.version } ?: 0) + 1
        val set = QuestionSet(
            id = UUID.randomUUID(),
            vacancyId = vacancyId,
            version = nextVersion,
            source = source,
            frozen = false,
            frozenAt = null,
            questions = StubData.toQuestions(StubData.questions, vacancy.requirements, QuestionOrigin.VACANCY),
            createdAt = Instant.now(),
        )
        store.questionSets[set.id] = set
        return set
    }

    fun getQuestionSet(owner: String, id: UUID): QuestionSet {
        val set = store.questionSet(id)
        requireOwned(owner, set.vacancyId)
        return set
    }

    fun updateQuestionSet(owner: String, id: UUID, questions: List<QuestionInput>): QuestionSet {
        val set = getQuestionSet(owner, id)
        if (set.frozen) {
            throw Conflict("QUESTION_SET_FROZEN", "Набор зафиксирован, создайте новую версию через revise")
        }
        if (questions.isEmpty()) throw ValidationFailed(mapOf("questions" to "нужен хотя бы один вопрос"))
        val updated = set.copy(
            questions = questions.mapIndexed { index, q ->
                Question(
                    id = q.id ?: UUID.randomUUID(),
                    ord = index + 1,
                    text = q.text,
                    requirementId = q.requirementId,
                    strongSignals = q.strongSignals,
                    origin = QuestionOrigin.VACANCY,
                )
            }
        )
        store.questionSets[id] = updated
        return updated
    }

    fun reviseQuestionSet(owner: String, id: UUID): QuestionSet {
        val set = getQuestionSet(owner, id)
        val nextVersion = (store.setsOf(set.vacancyId).maxOfOrNull { it.version } ?: 0) + 1
        val draft = set.copy(
            id = UUID.randomUUID(),
            version = nextVersion,
            frozen = false,
            frozenAt = null,
            createdAt = Instant.now(),
            questions = set.questions.map { it.copy(id = UUID.randomUUID()) },
        )
        store.questionSets[draft.id] = draft
        return draft
    }

    fun freezeQuestionSet(owner: String, id: UUID): QuestionSet {
        val set = getQuestionSet(owner, id)
        if (set.frozen) return set
        val frozen = set.copy(frozen = true, frozenAt = Instant.now())
        store.questionSets[id] = frozen
        return frozen
    }

    // ---------- интервью ----------

    fun createInterview(owner: String, input: InterviewInput): InterviewDetail {
        val vacancy = requireOwned(owner, input.vacancyId)
        if (input.candidateName.isBlank()) {
            throw ValidationFailed(mapOf("candidateName" to "не должно быть пустым"))
        }
        val set = store.activeSet(vacancy.id)
            ?: throw Conflict("QUESTION_SET_NOT_FROZEN", "У вакансии нет зафиксированного набора вопросов")

        val session = InterviewSession(
            id = UUID.randomUUID(),
            vacancyId = vacancy.id,
            questionSetId = set.id,
            questionSetVersion = set.version,
            candidateName = input.candidateName,
            resumeText = input.resumeText?.takeIf { it.isNotBlank() },
            candidateToken = UUID.randomUUID().toString().replace("-", ""),
            expiresAt = Instant.now().plus(props.interview.candidateLinkTtlDays, ChronoUnit.DAYS),
            createdAt = Instant.now(),
        )

        // Ядро снапшотится в план: карточка обязана показывать то, что видел кандидат (Р-13)
        set.questions.forEach { q ->
            session.plan.add(
                PlanItem(
                    questionId = UUID.randomUUID(),
                    ord = session.plan.size + 1,
                    kind = QuestionKind.CORE,
                    origin = QuestionOrigin.VACANCY,
                    text = q.text,
                    requirementId = q.requirementId,
                    strongSignals = q.strongSignals,
                )
            )
        }
        // Персональные вопросы генерятся автоматически, рекрутер их не правит (Р-12)
        session.resumeText?.let { resume ->
            StubData.personalQuestions(resume).forEach { q ->
                session.plan.add(
                    PlanItem(
                        questionId = UUID.randomUUID(),
                        ord = session.plan.size + 1,
                        kind = QuestionKind.PERSONAL,
                        origin = QuestionOrigin.RESUME,
                        text = q.text,
                        requirementId = null,
                        strongSignals = q.signals,
                    )
                )
            }
        }

        store.interviews[session.id] = session
        return detail(session)
    }

    fun listInterviews(owner: String, vacancyId: UUID?): List<InterviewListItem> {
        vacancyId?.let { requireOwned(owner, it) }
        return store.interviewsOf(vacancyId)
            .filter { store.vacancyOwner[it.vacancyId] == owner }
            .map { s ->
                InterviewListItem(
                    id = s.id,
                    candidateName = s.candidateName,
                    status = effectiveStatus(s),
                    recommendation = s.report?.recommendation,
                    overallScore = s.report?.overallScore,
                    answered = s.answered,
                    planned = s.planned,
                    createdAt = s.createdAt,
                    completedAt = s.completedAt,
                )
            }
    }

    fun getInterview(owner: String, id: UUID): InterviewDetail {
        val session = store.interview(id)
        requireOwned(owner, session.vacancyId)
        return detail(session)
    }

    fun report(owner: String, id: UUID): Report {
        val session = store.interview(id)
        requireOwned(owner, session.vacancyId)
        return session.report ?: throw Conflict("REPORT_NOT_READY", "Карточка ещё не готова")
    }

    fun reanalyze(owner: String, id: UUID): InterviewDetail {
        val session = store.interview(id)
        requireOwned(owner, session.vacancyId)
        if (session.status !in setOf(InterviewStatus.READY_REPORT, InterviewStatus.FAILED)) {
            throw InvalidState("Повторный анализ доступен только для готовой или упавшей карточки")
        }
        pipeline.reanalyze(session)
        return detail(session)
    }

    fun share(owner: String, id: UUID, ttlDays: Long?): ShareLink {
        val session = store.interview(id)
        requireOwned(owner, session.vacancyId)
        val days = ttlDays ?: props.interview.shareLinkTtlDays
        if (days !in 1..365) throw ValidationFailed(mapOf("ttlDays" to "должен быть от 1 до 365"))
        val token = UUID.randomUUID().toString().replace("-", "")
        val link = ShareLink(
            url = "${props.publicBaseUrl}/r/$token",
            token = token,
            expiresAt = Instant.now().plus(days, ChronoUnit.DAYS),
            revoked = false,
        )
        session.share = link
        return link
    }

    fun revokeShare(owner: String, id: UUID) {
        val session = store.interview(id)
        requireOwned(owner, session.vacancyId)
        session.share = session.share?.copy(revoked = true)
    }

    fun reportByShareToken(token: String): Report {
        val session = store.byShareToken(token)
        val link = session.share ?: throw NotFound("Ссылка не найдена")
        if (link.revoked) throw Forbidden("LINK_REVOKED", "Ссылка отозвана")
        if (link.expiresAt.isBefore(Instant.now())) throw LinkExpired()
        return session.report ?: throw Conflict("REPORT_NOT_READY", "Карточка ещё не готова")
    }

    // ---------- сессия кандидата ----------

    fun candidateState(token: String): CandidateState = state(store.byCandidateToken(token))

    fun consent(token: String): CandidateState {
        val session = store.byCandidateToken(token)
        requireStatus(session, InterviewStatus.CREATED)
        session.consentAt = Instant.now()
        session.status = InterviewStatus.READY
        return state(session)
    }

    fun start(token: String): CandidateState {
        val session = store.byCandidateToken(token)
        requireStatus(session, InterviewStatus.READY)
        session.status = InterviewStatus.IN_PROGRESS
        return state(session)
    }

    fun startAnswer(token: String, req: StartAnswerRequest): AnswerUpload {
        val session = store.byCandidateToken(token)
        requireStatus(session, InterviewStatus.IN_PROGRESS)
        if (session.processing != null) throw InvalidState("Предыдущий ответ ещё обрабатывается")

        val item = session.currentItem() ?: throw InvalidState("Текущего вопроса нет")
        if (item.questionId != req.questionId) throw InvalidState("Это не текущий вопрос интервью")
        if (item.answer != null) throw InvalidState("Ответ на этот вопрос уже начат")

        val contentType = req.contentType.ifBlank { "video/webm" }
        val answer = AnswerRecord(contentType = contentType, uploadStarted = true)
        val key = "interviews/${session.id}/answers/${answer.id}"
        answer.mediaKey = key
        item.answer = answer

        return AnswerUpload(
            answerId = answer.id,
            uploadUrl = s3.presignUpload(key, contentType),
            contentType = contentType,
            expiresAt = Instant.now().plus(props.s3.uploadUrlTtl),
        )
    }

    fun retryUpload(token: String, answerId: UUID): AnswerUpload {
        val session = store.byCandidateToken(token)
        val item = session.itemByAnswer(answerId) ?: throw NotFound("Ответ не найден")
        val answer = item.answer!!
        if (answer.status != null) throw InvalidState("Ответ уже обработан")
        val contentType = answer.contentType ?: "video/webm"
        return AnswerUpload(
            answerId = answer.id,
            uploadUrl = s3.presignUpload(answer.mediaKey!!, contentType),
            contentType = contentType,
            expiresAt = Instant.now().plus(props.s3.uploadUrlTtl),
        )
    }

    fun completeAnswer(token: String, answerId: UUID, durationMs: Long?): CandidateState {
        val session = store.byCandidateToken(token)
        requireStatus(session, InterviewStatus.IN_PROGRESS)
        val item = session.itemByAnswer(answerId) ?: throw NotFound("Ответ не найден")
        val answer = item.answer!!
        if (answer.status != null) throw InvalidState("Ответ уже обработан")
        answer.durationMs = durationMs
        session.processing = CandidateProcessing(answerId, ProcessingStage.SAVING)
        pipeline.processAnswer(session, answerId)
        return state(session)
    }

    fun skipQuestion(token: String, questionId: UUID): CandidateState {
        val session = store.byCandidateToken(token)
        requireStatus(session, InterviewStatus.IN_PROGRESS)
        if (session.processing != null) throw InvalidState("Предыдущий ответ ещё обрабатывается")
        val item = session.currentItem() ?: throw InvalidState("Текущего вопроса нет")
        if (item.questionId != questionId) throw InvalidState("Это не текущий вопрос интервью")
        pipeline.skipAnswer(session, item)
        return state(session)
    }

    fun recordEvent(token: String, req: AntifraudEventRequest) {
        val session = store.byCandidateToken(token)
        session.antifraud.add(AntifraudEventView(req.type, req.occurredAt ?: Instant.now()))
    }

    fun questionText(token: String, questionId: UUID): String {
        val session = store.byCandidateToken(token)
        return session.itemByQuestion(questionId)?.text ?: throw NotFound("Вопрос не найден")
    }

    private fun requireStatus(session: InterviewSession, expected: InterviewStatus) {
        val actual = effectiveStatus(session)
        if (actual == InterviewStatus.EXPIRED) throw LinkExpired()
        if (actual != expected) throw InvalidState("Недопустимо в статусе $actual")
    }

    private fun effectiveStatus(session: InterviewSession): InterviewStatus =
        if (session.status in setOf(InterviewStatus.CREATED, InterviewStatus.READY, InterviewStatus.IN_PROGRESS) &&
            session.expiresAt.isBefore(Instant.now())
        ) InterviewStatus.EXPIRED else session.status

    // ---------- маппинг ----------

    private fun detail(session: InterviewSession): InterviewDetail {
        val vacancy = store.vacancy(session.vacancyId)
        return InterviewDetail(
            id = session.id,
            vacancyId = vacancy.id,
            vacancyTitle = vacancy.title,
            questionSetVersion = session.questionSetVersion,
            candidateName = session.candidateName,
            resumeText = session.resumeText,
            status = effectiveStatus(session),
            candidateUrl = "${props.publicBaseUrl}/s/${session.candidateToken}",
            expiresAt = session.expiresAt,
            consentAt = session.consentAt,
            answered = session.answered,
            planned = session.planned,
            share = session.share,
            reportAvailable = session.report != null,
            failure = session.failure,
            createdAt = session.createdAt,
            completedAt = session.completedAt,
        )
    }

    private fun state(session: InterviewSession): CandidateState {
        val vacancy = store.vacancy(session.vacancyId)
        val status = effectiveStatus(session)
        val showQuestion = status == InterviewStatus.IN_PROGRESS && session.processing == null
        val item = if (showQuestion) session.currentItem() else null
        val requirements = store.requirementsOf(session.vacancyId)

        return CandidateState(
            status = status,
            vacancyTitle = vacancy.title,
            companyName = companyName,
            candidateName = session.candidateName,
            answered = session.answered,
            planned = session.planned,
            expectedDurationMinutes = (session.planned * 4).coerceIn(15, 40),
            maxAnswerDurationSec = props.interview.maxAnswerDurationSec,
            rules = listOf(
                "Вопросы задаются по одному: сначала показываются текстом, затем озвучиваются.",
                "На каждый вопрос одна попытка, вернуться к предыдущему нельзя.",
                "Ответ записывается на камеру и микрофон.",
                "Вопрос можно пропустить, но тогда компетенция останется непроверенной.",
                "На один ответ отводится до ${props.interview.maxAnswerDurationSec / 60} минут.",
            ),
            consentText = "Я согласен на запись видео и аудио моих ответов, а также на обработку " +
                "этих материалов и данных резюме для оценки моей кандидатуры. " +
                "[Текст-заглушка: юридическая формулировка появится позже.]",
            antifraudEnabled = true,
            currentQuestion = item?.let {
                CandidateQuestion(
                    id = it.questionId,
                    ord = it.ord,
                    kind = it.kind,
                    text = it.text,
                    audioUrl = "/api/s/${session.candidateToken}/questions/${it.questionId}/audio",
                    requirementText = requirements.find { r -> r.id == it.requirementId }?.text,
                )
            },
            processing = session.processing,
            message = when (status) {
                InterviewStatus.EXPIRED -> "Ссылка недействительна. Обратитесь к рекрутеру за новой."
                InterviewStatus.ANALYZING, InterviewStatus.READY_REPORT, InterviewStatus.FAILED ->
                    "Интервью отправлено. Результат получит рекрутер, отдельно вам он не приходит."
                else -> null
            },
        )
    }

    // ---------- демо-данные ----------

    @Synchronized
    fun demoSeed(owner: String): DemoSeedResult {
        val existing = store.vacancies.values.find { it.title == DEMO_TITLE }
        if (existing != null) {
            val set = store.activeSet(existing.id)!!
            return DemoSeedResult(existing.id, set.id)
        }
        val vacancy = createVacancy(
            owner,
            VacancyInput(
                title = DEMO_TITLE,
                grade = Grade.MIDDLE_PLUS,
                description = "Разработка и поддержка высоконагруженных микросервисов, интеграция с Kafka, " +
                    "проектирование Event Sourcing и CQRS, разработка API для межсервисного взаимодействия, " +
                    "настройка мониторинга и алертинга.",
                requirements = StubData.requirements,
            )
        )
        val set = freezeQuestionSet(
            owner,
            generateQuestionSet(owner, vacancy.id, QuestionSetSource.REFERENCE).id,
        )
        return DemoSeedResult(vacancy.id, set.id)
    }

    private companion object {
        const val DEMO_TITLE = "Middle+ Python Developer"
    }
}

private fun RequirementInput.toRequirement(id: UUID) = Requirement(
    id = id, text = text, kind = kind, weight = weight,
    stopFactor = stopFactor, notVerifiable = notVerifiable,
)

private fun QuestionSet.toRef() = QuestionSetRef(id, version, frozen, questions.size)

private fun Instant.plus(d: Duration): Instant = this.plusMillis(d.toMillis())
