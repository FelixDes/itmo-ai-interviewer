package com.itmo.napoleonit.aiinterviewer.stub

import com.itmo.napoleonit.aiinterviewer.web.dto.*
import java.time.Instant
import java.util.UUID

/**
 * ВРЕМЕННОЕ in-memory состояние скелета API.
 *
 * Схема БД уже есть (V1__init.sql), но пока каждый модуль не написан по-настоящему,
 * скелет держит данные в памяти: фронт может разрабатываться параллельно, а мы
 * не пишем персистентность дважды. Всё в этом пакете подлежит замене.
 */
class AnswerRecord(
    val id: UUID = UUID.randomUUID(),
    var mediaKey: String? = null,
    var contentType: String? = null,
    var durationMs: Long? = null,
    var status: AnswerStatus? = null,
    var uploadStarted: Boolean = false,
)

class PlanItem(
    val questionId: UUID,
    var ord: Int,
    val kind: QuestionKind,
    val origin: QuestionOrigin,
    val text: String,
    val requirementId: UUID?,
    val strongSignals: List<String>,
    val parentQuestionId: UUID? = null,
    var answer: AnswerRecord? = null,
)

class InterviewSession(
    val id: UUID,
    val vacancyId: UUID,
    val questionSetId: UUID,
    val questionSetVersion: Int,
    val candidateName: String,
    val resumeText: String?,
    val candidateToken: String,
    val expiresAt: Instant,
    val createdAt: Instant,
    val plan: MutableList<PlanItem> = mutableListOf(),
) {
    @Volatile var status: InterviewStatus = InterviewStatus.CREATED
    @Volatile var consentAt: Instant? = null
    @Volatile var completedAt: Instant? = null
    @Volatile var currentIndex: Int = 0
    @Volatile var processing: CandidateProcessing? = null
    @Volatile var share: ShareLink? = null
    @Volatile var report: Report? = null
    @Volatile var failure: InterviewFailure? = null
    @Volatile var followUpInserted: Boolean = false

    val antifraud: MutableList<AntifraudEventView> = mutableListOf()

    val answered: Int get() = plan.count { it.answer?.status != null }
    val planned: Int get() = plan.size

    fun currentItem(): PlanItem? = plan.getOrNull(currentIndex)

    fun itemByQuestion(questionId: UUID): PlanItem? = plan.find { it.questionId == questionId }

    fun itemByAnswer(answerId: UUID): PlanItem? = plan.find { it.answer?.id == answerId }
}
