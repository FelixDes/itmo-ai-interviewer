package com.itmo.napoleonit.aiinterviewer.web.dto

import java.time.Instant
import java.util.UUID

// ---------- перечисления (docs/api.md §2) ----------

enum class Grade { JUNIOR, MIDDLE, MIDDLE_PLUS, SENIOR, LEAD }
enum class RequirementKind { MUST, NICE }
enum class InterviewStatus { CREATED, READY, IN_PROGRESS, ANALYZING, READY_REPORT, FAILED, EXPIRED }
enum class QuestionKind { CORE, PERSONAL, FOLLOWUP }
enum class QuestionOrigin { VACANCY, RESUME, PREVIOUS_ANSWER }
enum class QuestionSetSource { LLM, REFERENCE }
enum class ProcessingStage { SAVING, TRANSCRIBING, EVALUATING, PREPARING_NEXT }
enum class AnswerStatus { EVALUATED, UNRATEABLE, SKIPPED, FAILED }
enum class Recommendation { FIT, NOT_FIT, NEEDS_CHECK }
enum class Confidence { LOW, MEDIUM, HIGH }
enum class RequirementStatus { CONFIRMED, PARTIAL, NOT_CONFIRMED, NOT_CHECKED }
enum class EvidenceBasis { ANSWER, RESUME, NONE }
enum class FindingType { FACT, INFERENCE, ASSUMPTION }
/**
 * События антифрода. Сигнал для человека, а не доказательство нарушения:
 * в карточку попадают отдельно от профессиональной оценки (Рамка §11).
 */
enum class AntifraudEventType {
    /** Кандидат ушёл со вкладки или свернул окно */
    TAB_HIDDEN,
    COPY,
    PASTE,
    /** Обнаружен второй экран: подсказки удобно держать на нём */
    MULTIPLE_SCREENS,
}

// ---------- аутентификация ----------

data class LoginRequest(val username: String, val password: String)
data class CurrentUser(val username: String, val displayName: String)

// ---------- вакансия ----------

data class RequirementInput(
    val id: UUID? = null,
    val text: String,
    val kind: RequirementKind,
    val weight: Int,
    val stopFactor: Boolean = false,
    val notVerifiable: Boolean = false,
)

data class Requirement(
    val id: UUID,
    val text: String,
    val kind: RequirementKind,
    val weight: Int,
    val stopFactor: Boolean,
    val notVerifiable: Boolean,
)

data class VacancyInput(
    val title: String,
    val grade: Grade,
    val description: String = "",
    val requirements: List<RequirementInput> = emptyList(),
)

data class QuestionSetRef(
    val id: UUID,
    val version: Int,
    val frozen: Boolean,
    val questionCount: Int,
)

data class Vacancy(
    val id: UUID,
    val title: String,
    val grade: Grade,
    val description: String,
    val requirements: List<Requirement>,
    val activeQuestionSet: QuestionSetRef?,
    val draftQuestionSet: QuestionSetRef?,
    val createdAt: Instant,
)

data class VacancyListItem(
    val id: UUID,
    val title: String,
    val grade: Grade,
    val interviewCount: Int,
    val hasFrozenQuestionSet: Boolean,
    val createdAt: Instant,
)

// ---------- вопросы ----------

data class QuestionInput(
    val id: UUID? = null,
    val text: String,
    val requirementId: UUID? = null,
    val strongSignals: List<String> = emptyList(),
)

data class Question(
    val id: UUID,
    val ord: Int,
    val text: String,
    val requirementId: UUID?,
    val strongSignals: List<String>,
    val origin: QuestionOrigin,
)

data class QuestionSet(
    val id: UUID,
    val vacancyId: UUID,
    val version: Int,
    val source: QuestionSetSource,
    val frozen: Boolean,
    val frozenAt: Instant?,
    val questions: List<Question>,
    val createdAt: Instant,
)

data class GenerateQuestionsRequest(val source: QuestionSetSource = QuestionSetSource.LLM)
data class UpdateQuestionsRequest(val questions: List<QuestionInput>)

// ---------- интервью ----------

data class InterviewInput(
    val vacancyId: UUID,
    val candidateName: String,
    val resumeText: String? = null,
)

data class ShareLink(
    val url: String,
    val token: String,
    val expiresAt: Instant,
    val revoked: Boolean,
)

data class ShareRequest(val ttlDays: Long? = null)

data class InterviewListItem(
    val id: UUID,
    val candidateName: String,
    val status: InterviewStatus,
    val recommendation: Recommendation?,
    val overallScore: Double?,
    val answered: Int,
    val planned: Int,
    val createdAt: Instant,
    val completedAt: Instant?,
)

data class InterviewFailure(val stage: String, val message: String)

data class InterviewDetail(
    val id: UUID,
    val vacancyId: UUID,
    val vacancyTitle: String,
    val questionSetVersion: Int,
    val candidateName: String,
    val resumeText: String?,
    val status: InterviewStatus,
    val candidateUrl: String,
    val expiresAt: Instant,
    val consentAt: Instant?,
    val answered: Int,
    val planned: Int,
    val share: ShareLink?,
    val reportAvailable: Boolean,
    val failure: InterviewFailure?,
    val createdAt: Instant,
    val completedAt: Instant?,
)

// ---------- сессия кандидата ----------

data class CandidateQuestion(
    val id: UUID,
    val ord: Int,
    val kind: QuestionKind,
    val text: String,
    val audioUrl: String,
    val requirementText: String?,
)

data class CandidateProcessing(val answerId: UUID, val stage: ProcessingStage)

data class CandidateState(
    val status: InterviewStatus,
    val vacancyTitle: String,
    val companyName: String,
    val candidateName: String,
    val answered: Int,
    val planned: Int,
    val expectedDurationMinutes: Int,
    val maxAnswerDurationSec: Int,
    val rules: List<String>,
    val consentText: String,
    val antifraudEnabled: Boolean,
    val currentQuestion: CandidateQuestion?,
    val processing: CandidateProcessing?,
    val message: String?,
)

data class StartAnswerRequest(val questionId: UUID, val contentType: String)

data class AnswerUpload(
    val answerId: UUID,
    val uploadUrl: String,
    val contentType: String,
    val expiresAt: Instant,
)

data class CompleteAnswerRequest(val durationMs: Long?)
data class AntifraudEventRequest(val type: AntifraudEventType, val occurredAt: Instant? = null)

// ---------- карточка результата ----------

data class Evidence(
    val answerId: UUID?,
    val questionOrd: Int,
    val quote: String,
    val startMs: Long?,
    val endMs: Long?,
)

data class Finding(
    val text: String,
    val type: FindingType,
    val evidence: List<Evidence> = emptyList(),
)

data class Scores(
    val technicalCorrectness: Int?,
    val depth: Int?,
    val relevance: Int?,
    val example: Int?,
    val personalContribution: Int?,
    val scaleAndMetrics: Int?,
)

data class RequirementVerdict(
    val requirementId: UUID,
    val text: String,
    val kind: RequirementKind,
    val weight: Int,
    val stopFactor: Boolean,
    val status: RequirementStatus,
    val basis: EvidenceBasis,
    val comment: String,
    val evidence: List<Evidence> = emptyList(),
)

data class AnswerReport(
    val answerId: UUID?,
    val questionId: UUID,
    val ord: Int,
    val kind: QuestionKind,
    val questionText: String,
    val requirementId: UUID?,
    val origin: QuestionOrigin,
    val parentQuestionId: UUID?,
    val status: AnswerStatus,
    val videoUrl: String?,
    val durationMs: Long?,
    val transcriptRefined: String?,
    val transcriptRaw: String?,
    val scores: Scores?,
    val confidence: Confidence?,
    val comment: String?,
    val evidence: List<Evidence> = emptyList(),
)

data class AntifraudEventView(val type: AntifraudEventType, val occurredAt: Instant)

data class TechnicalBlock(
    val antifraudEvents: List<AntifraudEventView>,
    val unrateableAnswers: Int,
    val failedAnswers: Int,
    val notes: List<String>,
)

data class ReportMeta(
    val model: String,
    val promptVersion: String,
    val rubricVersion: String,
    val questionSetVersion: Int,
    val generatedAt: Instant,
)

data class Report(
    val interviewId: UUID,
    val candidateName: String,
    val vacancyTitle: String,
    val vacancyGrade: Grade,
    val completedAt: Instant,
    val recommendation: Recommendation,
    val overallScore: Double,
    val confidence: Confidence,
    val summary: String,
    val requirementsMust: List<RequirementVerdict>,
    val requirementsNice: List<RequirementVerdict>,
    val answers: List<AnswerReport>,
    val strengths: List<Finding>,
    val risks: List<Finding>,
    val skillsFound: List<String>,
    val skillsNotChecked: List<String>,
    val nextStageQuestions: List<String>,
    val candidateFeedback: String,
    val technical: TechnicalBlock,
    val meta: ReportMeta,
)

data class DemoSeedResult(val vacancyId: UUID, val questionSetId: UUID)
