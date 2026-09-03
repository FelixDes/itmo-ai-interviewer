package com.itmo.napoleonit.aiinterviewer.domain

import com.itmo.napoleonit.aiinterviewer.web.dto.*
import java.time.Instant
import java.util.UUID

/**
 * Строки таблиц (V1__init.sql). Плоские, без объектных графов:
 * связи между агрегатами держим по id.
 */

data class VacancyRow(
    val id: UUID,
    val ownerUsername: String,
    val title: String,
    val grade: Grade,
    val description: String,
    val createdAt: Instant,
)

data class RequirementRow(
    val id: UUID,
    val vacancyId: UUID,
    val ord: Int,
    val text: String,
    val kind: RequirementKind,
    val weight: Int,
    val stopFactor: Boolean,
    val notVerifiable: Boolean,
    val deleted: Boolean,
) {
    fun toDto() = Requirement(id, text, kind, weight, stopFactor, notVerifiable)
}

data class QuestionSetRow(
    val id: UUID,
    val vacancyId: UUID,
    val version: Int,
    val source: QuestionSetSource,
    val frozen: Boolean,
    val frozenAt: Instant?,
    val createdAt: Instant,
)

data class QuestionRow(
    val id: UUID,
    val questionSetId: UUID,
    val ord: Int,
    val text: String,
    val requirementId: UUID?,
    val strongSignals: List<String>,
    val origin: QuestionOrigin,
) {
    fun toDto() = Question(id, ord, text, requirementId, strongSignals, origin)
}

/**
 * Снапшот вопроса в плане конкретного интервью (Р-13).
 * id этой строки — то, что API отдаёт как questionId.
 */
data class PlanItemRow(
    val id: UUID,
    val interviewId: UUID,
    val ord: Int,
    val kind: QuestionKind,
    val origin: QuestionOrigin,
    val originQuestionId: UUID?,
    val parentQuestionId: UUID?,
    val text: String,
    val requirementId: UUID?,
    val strongSignals: List<String>,
    val audioKey: String?,
)

/** Внутренние состояния ответа; терминальные совпадают с AnswerStatus из API. */
enum class AnswerState { RECORDING, UPLOADED, TRANSCRIBING, EVALUATING, EVALUATED, UNRATEABLE, SKIPPED, FAILED;

    /**
     * Кандидат с ответом закончил и получил следующий вопрос.
     * Оценка при этом может ещё считаться в фоне, поэтому это не то же самое,
     * что terminal: прогресс кандидата не должен зависеть от скорости модели.
     */
    val settled: Boolean get() = this != RECORDING && this != UPLOADED

    val terminal: AnswerStatus?
        get() = when (this) {
            EVALUATED -> AnswerStatus.EVALUATED
            UNRATEABLE -> AnswerStatus.UNRATEABLE
            SKIPPED -> AnswerStatus.SKIPPED
            FAILED -> AnswerStatus.FAILED
            else -> null
        }
}

data class AnswerRow(
    val id: UUID,
    val interviewQuestionId: UUID,
    val mediaKey: String?,
    val contentType: String?,
    val durationMs: Long?,
    val state: AnswerState,
    val createdAt: Instant,
    val completedAt: Instant?,
)

data class TranscriptRow(
    val id: UUID,
    val answerId: UUID,
    val rawText: String?,
    val refinedText: String?,
    val segments: List<TranscriptSegment>,
    val asrModel: String?,
)

data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

data class EvaluationRow(
    val id: UUID,
    val answerId: UUID,
    val scores: Scores?,
    val quotes: List<Evidence>,
    val confidence: Confidence?,
    val comment: String?,
    val model: String?,
    val promptVersion: String?,
)

data class InterviewRow(
    val id: UUID,
    val vacancyId: UUID,
    val questionSetId: UUID,
    val questionSetVersion: Int,
    val candidateName: String,
    val resumeText: String?,
    val status: InterviewStatus,
    val candidateToken: String,
    val expiresAt: Instant,
    val consentAt: Instant?,
    val createdAt: Instant,
    val completedAt: Instant?,
    val failureStage: String?,
    val failureMessage: String?,
)

data class ShareLinkRow(
    val id: UUID,
    val interviewId: UUID,
    val token: String,
    val expiresAt: Instant,
    val revokedAt: Instant?,
)

data class AntifraudRow(
    val id: UUID,
    val interviewId: UUID,
    val type: AntifraudEventType,
    val occurredAt: Instant,
)

enum class JobKind { ANSWER_PIPELINE, REPORT }
enum class JobState { PENDING, RUNNING, DONE, FAILED }

data class ProcessingJobRow(
    val id: UUID,
    val kind: JobKind,
    val state: JobState,
    val stage: ProcessingStage?,
    val interviewId: UUID?,
    val answerId: UUID?,
    val attempts: Int,
    val lastError: String?,
)
