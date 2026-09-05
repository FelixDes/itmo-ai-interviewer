package com.itmo.napoleonit.aiinterviewer.service

import com.itmo.napoleonit.aiinterviewer.config.AppProperties
import com.itmo.napoleonit.aiinterviewer.domain.*
import com.itmo.napoleonit.aiinterviewer.media.S3Service
import com.itmo.napoleonit.aiinterviewer.persistence.AnswerRepository
import com.itmo.napoleonit.aiinterviewer.persistence.InterviewRepository
import com.itmo.napoleonit.aiinterviewer.persistence.VacancyRepository
import com.itmo.napoleonit.aiinterviewer.tts.TtsEngine
import com.itmo.napoleonit.aiinterviewer.web.*
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Сессия кандидата. Доступ только по токену из ссылки, ни куки, ни логина.
 * Единственный источник правды для фронта — CandidateState целиком (Р-18).
 */
@Service
class CandidateService(
    private val interviews: InterviewRepository,
    private val answers: AnswerRepository,
    private val vacancies: VacancyRepository,
    private val processing: AnswerProcessingService,
    private val tts: TtsEngine,
    private val s3: S3Service,
    private val props: AppProperties,
) {
    private val companyName = "Napoleon IT"

    fun state(token: String): CandidateState = buildState(require(token))

    @Transactional
    fun consent(token: String): CandidateState {
        val row = require(token)
        requireStatus(row, InterviewStatus.CREATED)
        interviews.markConsent(row.id, Instant.now())
        return buildState(require(token))
    }

    @Transactional
    fun start(token: String): CandidateState {
        val row = require(token)
        requireStatus(row, InterviewStatus.READY)
        interviews.updateStatus(row.id, InterviewStatus.IN_PROGRESS)
        return buildState(require(token))
    }

    @Transactional
    fun startAnswer(token: String, request: StartAnswerRequest): AnswerUpload {
        val row = require(token)
        requireStatus(row, InterviewStatus.IN_PROGRESS)
        requireNotProcessing(row)

        val current = currentItem(row) ?: throw InvalidState("Текущего вопроса нет")
        if (current.id != request.questionId) throw InvalidState("Это не текущий вопрос интервью")
        if (answers.byQuestion(current.id) != null) throw InvalidState("Ответ на этот вопрос уже начат")

        val contentType = request.contentType.ifBlank { "video/webm" }
        val answer = AnswerRow(
            id = UUID.randomUUID(), interviewQuestionId = current.id,
            mediaKey = "interviews/${row.id}/answers/${UUID.randomUUID()}",
            contentType = contentType, durationMs = null, state = AnswerState.RECORDING,
            createdAt = Instant.now(), completedAt = null,
        )
        answers.insert(answer)
        return upload(answer)
    }

    fun retryUpload(token: String, answerId: UUID): AnswerUpload {
        val row = require(token)
        val answer = answers.find(answerId) ?: throw NotFound("Ответ не найден")
        requireBelongs(row, answer)
        if (answer.state.terminal != null) throw InvalidState("Ответ уже обработан")
        return upload(answer)
    }

    @Transactional
    fun completeAnswer(token: String, answerId: UUID, durationMs: Long?): CandidateState {
        val row = require(token)
        requireStatus(row, InterviewStatus.IN_PROGRESS)
        val answer = answers.find(answerId) ?: throw NotFound("Ответ не найден")
        requireBelongs(row, answer)
        if (answer.state != AnswerState.RECORDING) throw InvalidState("Ответ уже отправлен в обработку")

        answers.complete(answerId, AnswerState.UPLOADED, durationMs, Instant.now())
        processing.enqueue(row.id, answerId)
        return buildState(row)
    }

    @Transactional
    fun skipQuestion(token: String, questionId: UUID): CandidateState {
        val row = require(token)
        requireStatus(row, InterviewStatus.IN_PROGRESS)
        requireNotProcessing(row)
        val current = currentItem(row) ?: throw InvalidState("Текущего вопроса нет")
        if (current.id != questionId) throw InvalidState("Это не текущий вопрос интервью")
        processing.recordSkip(row.id, current)
        return buildState(require(token))
    }

    fun recordEvent(token: String, request: AntifraudEventRequest) {
        val row = require(token)
        interviews.insertAntifraud(
            AntifraudRow(UUID.randomUUID(), row.id, request.type, request.occurredAt ?: Instant.now())
        )
    }

    /**
     * Озвучка лениво, с кэшем в S3 (Р-22). Голос входит в ключ: иначе кандидат,
     * сменивший голос, получал бы чужую озвучку из кэша.
     */
    fun audio(token: String, questionId: UUID): Pair<ByteArray, String> {
        val row = require(token)
        val item = interviews.plan(row.id).find { it.id == questionId }
            ?: throw NotFound("Вопрос не найден")
        return speak(item.text, voiceOf(row))
    }

    /** Короткая фраза тем же голосом: кандидат слушает её перед началом. */
    fun voiceSample(token: String, voice: String): Pair<ByteArray, String> {
        require(token)
        return speak(SAMPLE_PHRASE, requireKnownVoice(voice))
    }

    @Transactional
    fun chooseVoice(token: String, voice: String): CandidateState {
        val row = require(token)
        if (effectiveStatus(row) == InterviewStatus.EXPIRED) throw LinkExpired()
        // Менять голос посреди интервью можно: на оценку он не влияет
        interviews.setVoice(row.id, requireKnownVoice(voice))
        return buildState(require(token))
    }

    private fun speak(text: String, voice: String): Pair<ByteArray, String> {
        val key = "tts/${tts.model}/$voice/${sha256(text)}.wav"
        s3.get(key)?.let { return it to tts.contentType }
        val audio = tts.synthesize(text, voice)
        s3.put(key, audio, tts.contentType)
        return audio to tts.contentType
    }

    private fun voiceOf(row: InterviewRow): String =
        row.ttsVoice ?: props.tts.defaultVoice

    private fun requireKnownVoice(voice: String): String {
        val known = props.tts.voices.map { it.id }
        if (voice !in known) {
            throw ValidationFailed(mapOf("voice" to "неизвестный голос, доступны: ${known.joinToString()}"))
        }
        return voice
    }

    // ---------- вспомогательное ----------

    private fun require(token: String): InterviewRow =
        interviews.byCandidateToken(token) ?: throw NotFound("Ссылка не найдена")

    private fun requireStatus(row: InterviewRow, expected: InterviewStatus) {
        val actual = effectiveStatus(row)
        if (actual == InterviewStatus.EXPIRED) throw LinkExpired()
        if (actual != expected) throw InvalidState("Недопустимо в статусе $actual")
    }

    private fun requireNotProcessing(row: InterviewRow) {
        if (answers.runningJob(row.id) != null) throw InvalidState("Предыдущий ответ ещё обрабатывается")
    }

    private fun requireBelongs(row: InterviewRow, answer: AnswerRow) {
        val item = interviews.planItem(answer.interviewQuestionId)
        if (item == null || item.interviewId != row.id) throw NotFound("Ответ не найден")
    }

    private fun upload(answer: AnswerRow) = AnswerUpload(
        answerId = answer.id,
        uploadUrl = s3.presignUpload(answer.mediaKey!!, answer.contentType!!),
        contentType = answer.contentType,
        expiresAt = Instant.now().plusMillis(props.s3.uploadUrlTtl.toMillis()),
    )

    /** Текущий вопрос — первый в плане без ответа. */
    private fun currentItem(row: InterviewRow): PlanItemRow? {
        val answered = answers.byInterview(row.id).map { it.interviewQuestionId }.toSet()
        return interviews.plan(row.id).firstOrNull { it.id !in answered }
    }

    private fun buildState(row: InterviewRow): CandidateState {
        val vacancy = vacancies.find(row.vacancyId)!!
        val status = effectiveStatus(row)
        val plan = interviews.plan(row.id)
        val answerRows = answers.byInterview(row.id)
        val job = answers.runningJob(row.id)

        val showQuestion = status == InterviewStatus.IN_PROGRESS && job == null
        val current = if (showQuestion) currentItem(row) else null

        // Подстраховка: вопросов не осталось, а статус ещё не переключился.
        // Кандидату честнее показать «интервью отправлено», чем пустой экран.
        val visibleStatus =
            if (status == InterviewStatus.IN_PROGRESS && job == null && current == null) InterviewStatus.ANALYZING
            else status
        val requirements = vacancies.requirements(row.vacancyId)

        return CandidateState(
            status = visibleStatus,
            vacancyTitle = vacancy.title,
            companyName = companyName,
            candidateName = row.candidateName,
            answered = answerRows.count { it.state.settled },
            planned = plan.size,
            expectedDurationMinutes = (plan.size * 4).coerceIn(15, 40),
            maxAnswerDurationSec = props.interview.maxAnswerDurationSec,
            rules = listOf(
                "Вопросы задаются по одному: сначала показываются текстом, затем озвучиваются.",
                "На каждый вопрос одна попытка, вернуться к предыдущему нельзя.",
                "Ответ записывается на камеру и микрофон.",
                "Вопрос можно пропустить, но тогда компетенция останется непроверенной.",
                "На один ответ отводится до ${props.interview.maxAnswerDurationSec / 60} минут.",
                "Отвечать нужно на одном экране: второй монитор потребуется отключить.",
            ),
            consentText = "Я согласен на запись видео и аудио моих ответов, а также на обработку " +
                "этих материалов и данных резюме для оценки моей кандидатуры. " +
                "[Текст-заглушка: юридическая формулировка появится позже.]",
            antifraudEnabled = true,
            voices = props.tts.voices.map { VoiceOption(it.id, it.name) },
            voice = voiceOf(row),
            currentQuestion = current?.let {
                CandidateQuestion(
                    id = it.id,
                    ord = it.ord,
                    kind = it.kind,
                    text = it.text,
                    audioUrl = "/api/s/${row.candidateToken}/questions/${it.id}/audio",
                    requirementText = requirements.find { r -> r.id == it.requirementId }?.text,
                )
            },
            processing = job?.answerId?.let { CandidateProcessing(it, job.stage ?: ProcessingStage.SAVING) },
            message = when (visibleStatus) {
                InterviewStatus.EXPIRED -> "Ссылка недействительна. Обратитесь к рекрутеру за новой."
                InterviewStatus.ANALYZING, InterviewStatus.READY_REPORT, InterviewStatus.FAILED ->
                    "Интервью отправлено. Результат получит рекрутер, отдельно вам он не приходит."
                else -> null
            },
        )
    }

    private companion object {
        const val SAMPLE_PHRASE =
            "Здравствуйте. Я задам несколько вопросов о вашем опыте. " +
                "Отвечайте так, как рассказали бы коллеге."
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
}
