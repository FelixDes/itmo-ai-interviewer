package com.itmo.napoleonit.aiinterviewer.service

import com.itmo.napoleonit.aiinterviewer.config.AppProperties
import com.itmo.napoleonit.aiinterviewer.domain.*
import com.itmo.napoleonit.aiinterviewer.evaluation.*
import com.itmo.napoleonit.aiinterviewer.llm.Untrusted
import com.itmo.napoleonit.aiinterviewer.media.S3Service
import com.itmo.napoleonit.aiinterviewer.persistence.*
import com.itmo.napoleonit.aiinterviewer.questions.FollowUpContext
import com.itmo.napoleonit.aiinterviewer.questions.FollowUpDecision
import com.itmo.napoleonit.aiinterviewer.questions.FollowUpGenerator
import com.itmo.napoleonit.aiinterviewer.transcription.AsrEngine
import com.itmo.napoleonit.aiinterviewer.transcription.QuestionAwareAsr
import com.itmo.napoleonit.aiinterviewer.transcription.TranscriptRefiner
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Обработка ответа: ASR -> выравнивание терминов -> оценка -> решение об уточнении (Р-8).
 *
 * Пока без стриминга: ответ записан целиком, потом обработали. Точка перехода
 * на потоковый ASR — замена AsrEngine, домен не меняется.
 */
@Service
class AnswerProcessingService(
    private val interviews: InterviewRepository,
    private val answers: AnswerRepository,
    private val vacancies: VacancyRepository,
    private val asr: AsrEngine,
    private val refiner: TranscriptRefiner,
    private val evaluator: AnswerEvaluator,
    private val followUps: FollowUpGenerator,
    private val reportBuilders: ReportBuilders,
    private val s3: S3Service,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val workers = Executors.newFixedThreadPool(4)

    @PreDestroy
    fun shutdown() {
        workers.shutdownNow()
    }

    fun enqueue(interviewId: UUID, answerId: UUID) {
        val job = ProcessingJobRow(
            id = UUID.randomUUID(), kind = JobKind.ANSWER_PIPELINE, state = JobState.PENDING,
            stage = ProcessingStage.SAVING, interviewId = interviewId, answerId = answerId,
            attempts = 0, lastError = null,
        )
        answers.upsertJob(job)
        workers.submit { runPipeline(job) }
    }

    /**
     * Синхронная часть — только то, без чего нельзя показать следующий вопрос:
     * расшифровка и решение об уточнении. Полная оценка по рубрике уходит в фон,
     * потому что кандидату она не нужна, а ждать её между вопросами — минуты.
     */
    private fun runPipeline(job: ProcessingJobRow) {
        val answerId = job.answerId!!
        val interviewId = job.interviewId!!
        try {
            stage(job, ProcessingStage.SAVING)
            val answer = answers.find(answerId) ?: return
            val item = interviews.planItem(answer.interviewQuestionId) ?: return

            stage(job, ProcessingStage.TRANSCRIBING)
            answers.setState(answerId, AnswerState.TRANSCRIBING)
            // Заглушке нужен текст вопроса, чтобы ответить по теме; боевой ASR его игнорирует
            (asr as? QuestionAwareAsr)?.rememberQuestion(answer.mediaKey ?: "", item.text)
            val asrResult = asr.transcribe(answer.mediaKey ?: "", answer.contentType)

            if (!asrResult.usable || asrResult.text.isBlank()) {
                // Рамка §7 и §8: честное «оценить нельзя», а не низкий балл
                answers.complete(answerId, AnswerState.UNRATEABLE, null, Instant.now())
                answers.upsertJob(job.copy(state = JobState.DONE, stage = null))
                closeIfLastAnswer(interviewId)
                return
            }

            val refined = refiner.refine(asrResult.text)

            // Кандидат может попытаться обратиться к модели голосом. Изоляция
            // в промпте уже стоит, но рекрутеру полезно знать о самой попытке.
            val markers = Untrusted.detectInjection(refined)
            if (markers.isNotEmpty()) {
                log.warn("Ответ {} похож на попытку внедрения: {}", answerId, markers)
                interviews.insertAntifraud(
                    AntifraudRow(
                        UUID.randomUUID(), interviewId,
                        AntifraudEventType.PROMPT_INJECTION, Instant.now(),
                    )
                )
            }

            answers.saveTranscript(
                TranscriptRow(
                    id = UUID.randomUUID(), answerId = answerId, rawText = asrResult.text,
                    refinedText = refined, segments = asrResult.segments, asrModel = asrResult.model,
                )
            )

            stage(job, ProcessingStage.PREPARING_NEXT)
            maybeAskFollowUp(interviewId, item, refined)

            // Ответ ещё не оценён, но кандидат с ним закончил: даём следующий вопрос
            answers.setState(answerId, AnswerState.EVALUATING)
            answers.upsertJob(job.copy(state = JobState.DONE, stage = null))

            workers.submit { evaluate(answerId, interviewId, item, refined, asrResult) }
        } catch (e: Exception) {
            log.error("Пайплайн ответа {} упал", answerId, e)
            runCatching { answers.complete(answerId, AnswerState.FAILED, null, Instant.now()) }
            answers.upsertJob(job.copy(state = JobState.FAILED, stage = null, lastError = e.message))
            // Интервью не должно вставать из-за одного ответа
            runCatching { closeIfLastAnswer(interviewId) }
        }
    }

    /** Фоновая часть: оценка по рубрике. Её результат нужен только карточке. */
    private fun evaluate(
        answerId: UUID,
        interviewId: UUID,
        item: PlanItemRow,
        transcript: String,
        asrResult: com.itmo.napoleonit.aiinterviewer.transcription.AsrResult,
    ) {
        try {
            val evaluation = evaluator.evaluate(
                AnswerEvaluationContext(
                    answerId = answerId, questionOrd = item.ord, questionText = item.text,
                    strongSignals = item.strongSignals, transcript = transcript,
                    segments = asrResult.segments,
                )
            )
            answers.saveEvaluation(
                EvaluationRow(
                    id = UUID.randomUUID(), answerId = answerId, scores = evaluation.scores,
                    quotes = evaluation.quotes, confidence = evaluation.confidence,
                    comment = evaluation.comment, model = evaluation.model,
                    promptVersion = evaluation.promptVersion,
                )
            )
            answers.complete(answerId, AnswerState.EVALUATED, null, Instant.now())
        } catch (e: Exception) {
            log.error("Оценка ответа {} упала", answerId, e)
            runCatching { answers.complete(answerId, AnswerState.FAILED, null, Instant.now()) }
        } finally {
            runCatching { closeIfLastAnswer(interviewId) }
        }
    }

    /** Адаптивное уточнение (Р-1). При ошибке генерации интервью идёт по базовому плану. */
    private fun maybeAskFollowUp(interviewId: UUID, item: PlanItemRow, transcript: String) {
        runCatching {
            val plan = interviews.plan(interviewId)
            val alreadyAsked = plan.count { it.parentQuestionId == item.id }
            val decision = followUps.decide(
                FollowUpContext(
                    questionText = item.text,
                    strongSignals = item.strongSignals,
                    transcript = transcript,
                    followUpsForThisAnswer = alreadyAsked,
                    alreadyAsked = plan.filter { it.kind == QuestionKind.FOLLOWUP }
                        .takeLast(5).map { it.text },
                    maxFollowUpsPerAnswer = props.interview.maxFollowupsPerAnswer,
                    totalQuestions = plan.size,
                    maxTotalQuestions = props.interview.maxTotalQuestions,
                )
            )
            if (decision is FollowUpDecision.Ask) {
                interviews.shiftOrdsFrom(interviewId, item.ord + 1)
                interviews.insertPlanItem(
                    PlanItemRow(
                        id = UUID.randomUUID(), interviewId = interviewId, ord = item.ord + 1,
                        kind = QuestionKind.FOLLOWUP, origin = QuestionOrigin.PREVIOUS_ANSWER,
                        originQuestionId = null, parentQuestionId = item.id, text = decision.text,
                        requirementId = item.requirementId, strongSignals = decision.strongSignals,
                        audioKey = null,
                    )
                )
            }
        }.onFailure { log.warn("Генерация уточнения не удалась, идём по базовому плану", it) }
    }

    private fun closeIfLastAnswer(interviewId: UUID) {
        val plan = interviews.plan(interviewId)
        val answered = answers.byInterview(interviewId).count { it.state.terminal != null }
        if (answered < plan.size) return

        interviews.markCompleted(interviewId, Instant.now())
        workers.submit { buildReport(interviewId) }
    }

    fun buildReport(interviewId: UUID) {
        try {
            val interview = interviews.find(interviewId) ?: return
            val vacancy = vacancies.find(interview.vacancyId) ?: return
            val plan = interviews.plan(interviewId)
            val answerByQuestion = answers.byInterview(interviewId).associateBy { it.interviewQuestionId }

            val items = plan.map { p ->
                val answer = answerByQuestion[p.id]
                ReportItem(
                    plan = p,
                    answer = answer,
                    transcript = answer?.let { answers.transcript(it.id) },
                    evaluation = answer?.let { answers.evaluation(it.id) },
                    videoUrl = answer?.mediaKey?.let { s3.presignDownload(it) },
                )
            }

            val report = reportBuilders.of(vacancy.evaluationMode).build(
                ReportContext(
                    interview = interview,
                    vacancy = vacancy,
                    requirements = vacancies.requirements(interview.vacancyId),
                    items = items,
                    antifraud = interviews.antifraud(interviewId),
                )
            )
            interviews.saveReport(interviewId, report)
            interviews.clearFailure(interviewId)
            interviews.updateStatus(interviewId, InterviewStatus.READY_REPORT)
        } catch (e: Exception) {
            log.error("Сборка карточки для $interviewId упала", e)
            interviews.markFailed(interviewId, "REPORT", e.message ?: e.javaClass.simpleName)
        }
    }

    fun rebuildReportAsync(interviewId: UUID) {
        workers.submit { buildReport(interviewId) }
    }

    /** Пропуск: ответа нет, компетенция остаётся непроверенной (не низкий балл). */
    fun recordSkip(interviewId: UUID, item: PlanItemRow) {
        val answer = AnswerRow(
            id = UUID.randomUUID(), interviewQuestionId = item.id, mediaKey = null, contentType = null,
            durationMs = null, state = AnswerState.SKIPPED, createdAt = Instant.now(), completedAt = Instant.now(),
        )
        answers.insert(answer)
        closeIfLastAnswer(interviewId)
    }

    private fun stage(job: ProcessingJobRow, stage: ProcessingStage) {
        answers.upsertJob(job.copy(state = JobState.RUNNING, stage = stage))
    }
}
