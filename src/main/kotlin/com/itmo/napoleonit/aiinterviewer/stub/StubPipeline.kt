package com.itmo.napoleonit.aiinterviewer.stub

import com.itmo.napoleonit.aiinterviewer.config.AppProperties
import com.itmo.napoleonit.aiinterviewer.media.S3Service
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Имитация обработки ответа: SAVING -> TRANSCRIBING -> EVALUATING -> PREPARING_NEXT.
 *
 * Нужна, чтобы фронт разрабатывал экран ожидания и поллинг против реального
 * поведения, а не против мгновенного ответа. Реальные ASR и LLM встанут на это
 * место позже (Р-8), контракт не поменяется.
 */
@Component
class StubPipeline(
    private val store: StubStore,
    private val s3: S3Service,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val scheduler = Executors.newScheduledThreadPool(2)

    @PreDestroy
    fun shutdown() = scheduler.shutdownNow().let { }

    fun processAnswer(session: InterviewSession, answerId: UUID) {
        val stages = listOf(
            0L to ProcessingStage.SAVING,
            1500L to ProcessingStage.TRANSCRIBING,
            3000L to ProcessingStage.EVALUATING,
            4500L to ProcessingStage.PREPARING_NEXT,
        )
        stages.forEach { (delay, stage) ->
            scheduler.schedule({
                session.processing = CandidateProcessing(answerId, stage)
            }, delay, TimeUnit.MILLISECONDS)
        }
        scheduler.schedule({ finishAnswer(session, answerId) }, 6000, TimeUnit.MILLISECONDS)
    }

    private fun finishAnswer(session: InterviewSession, answerId: UUID) {
        runCatching {
            val item = session.itemByAnswer(answerId) ?: return
            item.answer?.status = AnswerStatus.EVALUATED
            session.processing = null
            maybeInsertFollowUp(session, item)
            advance(session)
        }.onFailure { log.error("Обработка ответа $answerId упала", it) }
    }

    /**
     * Демонстрация адаптивности (Р-1, критерий готовности Рамки §13):
     * после второго ответа один раз вставляем уточняющий вопрос.
     */
    private fun maybeInsertFollowUp(session: InterviewSession, item: PlanItem) {
        if (session.followUpInserted) return
        if (item.kind == QuestionKind.FOLLOWUP) return
        if (session.currentIndex != 1) return
        if (session.plan.size >= props.interview.maxTotalQuestions) return

        val followUp = PlanItem(
            questionId = UUID.randomUUID(),
            ord = 0,
            kind = QuestionKind.FOLLOWUP,
            origin = QuestionOrigin.PREVIOUS_ANSWER,
            text = "Вы описали общий подход. Приведите конкретный пример из своего проекта: " +
                "какой это был сервис, какой объём сообщений и что именно делали лично вы?",
            requirementId = item.requirementId,
            strongSignals = listOf("Конкретный проект", "Числа", "Личный вклад"),
            parentQuestionId = item.questionId,
        )
        session.plan.add(session.currentIndex + 1, followUp)
        session.plan.forEachIndexed { i, p -> p.ord = i + 1 }
        session.followUpInserted = true
    }

    private fun advance(session: InterviewSession) {
        session.currentIndex += 1
        if (session.currentIndex >= session.plan.size) {
            session.status = InterviewStatus.ANALYZING
            session.completedAt = Instant.now()
            scheduler.schedule({ buildReport(session) }, 5000, TimeUnit.MILLISECONDS)
        }
    }

    fun skipAnswer(session: InterviewSession, item: PlanItem) {
        item.answer = AnswerRecord(status = AnswerStatus.SKIPPED)
        session.processing = null
        advance(session)
    }

    fun buildReport(session: InterviewSession) {
        runCatching {
            session.report = StubReportFactory.build(session, store, s3)
            session.status = InterviewStatus.READY_REPORT
        }.onFailure {
            log.error("Сборка карточки для ${session.id} упала", it)
            session.failure = InterviewFailure("REPORT", it.message ?: "Ошибка сборки карточки")
            session.status = InterviewStatus.FAILED
        }
    }

    fun reanalyze(session: InterviewSession) {
        session.failure = null
        session.status = InterviewStatus.ANALYZING
        scheduler.schedule({ buildReport(session) }, 4000, TimeUnit.MILLISECONDS)
    }
}
