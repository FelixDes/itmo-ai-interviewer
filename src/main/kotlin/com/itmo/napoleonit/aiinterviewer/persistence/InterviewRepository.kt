package com.itmo.napoleonit.aiinterviewer.persistence

import com.itmo.napoleonit.aiinterviewer.domain.*
import com.itmo.napoleonit.aiinterviewer.web.dto.InterviewStatus
import com.itmo.napoleonit.aiinterviewer.web.dto.Report
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class InterviewRepository(private val db: JdbcClient, private val json: Json) {

    fun insert(row: InterviewRow) {
        db.sql(
            """
            insert into interview (id, vacancy_id, question_set_id, question_set_version, candidate_name,
                                   resume_text, status, candidate_token, expires_at, created_at)
            values (:id, :vacancyId, :setId, :version, :name, :resume, :status, :token, :expiresAt, :createdAt)
            """
        )
            .param("id", row.id).param("vacancyId", row.vacancyId).param("setId", row.questionSetId)
            .param("version", row.questionSetVersion).param("name", row.candidateName)
            .param("resume", row.resumeText).param("status", row.status.name)
            .param("token", row.candidateToken).param("expiresAt", row.expiresAt.offset())
            .param("createdAt", row.createdAt.offset())
            .update()
    }

    fun find(id: UUID): InterviewRow? = db.sql("select * from interview where id = :id")
        .param("id", id).query(::map).optional().orElse(null)

    fun byCandidateToken(token: String): InterviewRow? =
        db.sql("select * from interview where candidate_token = :token")
            .param("token", token).query(::map).optional().orElse(null)

    fun byOwner(owner: String, vacancyId: UUID?): List<InterviewRow> {
        val sql = StringBuilder(
            """
            select i.* from interview i
            join vacancy v on v.id = i.vacancy_id
            where v.owner_username = :owner
            """
        )
        if (vacancyId != null) sql.append(" and i.vacancy_id = :vacancyId")
        sql.append(" order by i.created_at desc")
        val spec = db.sql(sql.toString()).param("owner", owner)
        if (vacancyId != null) spec.param("vacancyId", vacancyId)
        return spec.query(::map).list()
    }

    fun countByVacancy(vacancyId: UUID): Int =
        db.sql("select count(*) from interview where vacancy_id = :id")
            .param("id", vacancyId).query(Int::class.java).single()

    fun updateStatus(id: UUID, status: InterviewStatus) {
        db.sql("update interview set status = :status where id = :id")
            .param("id", id).param("status", status.name).update()
    }

    fun markConsent(id: UUID, at: Instant) {
        db.sql("update interview set consent_at = :at, status = :status where id = :id")
            .param("id", id).param("at", at.offset()).param("status", InterviewStatus.READY.name).update()
    }

    fun markCompleted(id: UUID, at: Instant) {
        db.sql("update interview set status = :status, completed_at = :at where id = :id")
            .param("id", id).param("status", InterviewStatus.ANALYZING.name).param("at", at.offset()).update()
    }

    fun markFailed(id: UUID, stage: String, message: String) {
        db.sql(
            """
            update interview set status = :status, failure_stage = :stage, failure_message = :message
            where id = :id
            """
        )
            .param("id", id).param("status", InterviewStatus.FAILED.name)
            .param("stage", stage).param("message", message.take(2000)).update()
    }

    fun clearFailure(id: UUID) {
        db.sql("update interview set failure_stage = null, failure_message = null where id = :id")
            .param("id", id).update()
    }

    // ---------- план интервью ----------

    fun insertPlanItem(row: PlanItemRow) {
        db.sql(
            """
            insert into interview_question (id, interview_id, ord, kind, origin, origin_question_id,
                                            parent_question_id, text, requirement_id, strong_signals, audio_key)
            values (:id, :interviewId, :ord, :kind, :origin, :originQuestionId,
                    :parentQuestionId, :text, :requirementId, cast(:signals as jsonb), :audioKey)
            """
        )
            .param("id", row.id).param("interviewId", row.interviewId).param("ord", row.ord)
            .param("kind", row.kind.name).param("origin", row.origin.name)
            .param("originQuestionId", row.originQuestionId).param("parentQuestionId", row.parentQuestionId)
            .param("text", row.text).param("requirementId", row.requirementId)
            .param("signals", json.write(row.strongSignals)).param("audioKey", row.audioKey)
            .update()
    }

    fun plan(interviewId: UUID): List<PlanItemRow> =
        db.sql("select * from interview_question where interview_id = :id order by ord")
            .param("id", interviewId).query(::mapPlanItem).list()

    fun planItem(id: UUID): PlanItemRow? =
        db.sql("select * from interview_question where id = :id")
            .param("id", id).query(::mapPlanItem).optional().orElse(null)

    /** Сдвигает хвост плана, чтобы вставить уточняющий вопрос сразу после текущего. */
    fun shiftOrdsFrom(interviewId: UUID, fromOrd: Int) {
        db.sql(
            """
            update interview_question set ord = ord + 1
            where interview_id = :id and ord >= :fromOrd
            """
        ).param("id", interviewId).param("fromOrd", fromOrd).update()
    }

    fun setVoice(interviewId: UUID, voice: String) {
        db.sql("update interview set tts_voice = :voice where id = :id")
            .param("id", interviewId).param("voice", voice).update()
    }

    fun setAudioKey(planItemId: UUID, key: String) {
        db.sql("update interview_question set audio_key = :key where id = :id")
            .param("id", planItemId).param("key", key).update()
    }

    // ---------- ссылки и события ----------

    fun insertShare(row: ShareLinkRow) {
        db.sql(
            """
            insert into share_link (id, interview_id, token, expires_at, revoked_at, created_at)
            values (:id, :interviewId, :token, :expiresAt, null, now())
            """
        )
            .param("id", row.id).param("interviewId", row.interviewId).param("token", row.token)
            .param("expiresAt", row.expiresAt.offset())
            .update()
    }

    fun activeShare(interviewId: UUID): ShareLinkRow? =
        db.sql(
            """
            select * from share_link where interview_id = :id
            order by created_at desc limit 1
            """
        ).param("id", interviewId).query(::mapShare).optional().orElse(null)

    fun shareByToken(token: String): ShareLinkRow? =
        db.sql("select * from share_link where token = :token")
            .param("token", token).query(::mapShare).optional().orElse(null)

    fun revokeShares(interviewId: UUID, at: Instant) {
        db.sql("update share_link set revoked_at = :at where interview_id = :id and revoked_at is null")
            .param("id", interviewId).param("at", at.offset()).update()
    }

    fun insertAntifraud(row: AntifraudRow) {
        db.sql(
            """
            insert into antifraud_event (id, interview_id, type, occurred_at)
            values (:id, :interviewId, :type, :at)
            """
        )
            .param("id", row.id).param("interviewId", row.interviewId)
            .param("type", row.type.name).param("at", row.occurredAt.offset())
            .update()
    }

    fun antifraud(interviewId: UUID): List<AntifraudRow> =
        db.sql("select * from antifraud_event where interview_id = :id order by occurred_at")
            .param("id", interviewId).query(::mapAntifraud).list()

    // ---------- карточка ----------

    fun saveReport(interviewId: UUID, report: Report) {
        db.sql(
            """
            insert into report (id, interview_id, recommendation, overall_score, confidence, payload,
                                model, prompt_version, rubric_version, question_set_version)
            values (:id, :interviewId, :recommendation, :score, :confidence, cast(:payload as jsonb),
                    :model, :promptVersion, :rubricVersion, :setVersion)
            on conflict (interview_id) do update set
                recommendation = excluded.recommendation,
                overall_score = excluded.overall_score,
                confidence = excluded.confidence,
                payload = excluded.payload,
                model = excluded.model,
                prompt_version = excluded.prompt_version,
                rubric_version = excluded.rubric_version,
                question_set_version = excluded.question_set_version,
                created_at = now()
            """
        )
            .param("id", UUID.randomUUID()).param("interviewId", interviewId)
            .param("recommendation", report.recommendation.name)
            .param("score", report.overallScore)
            .param("confidence", report.confidence.name)
            .param("payload", json.write(report))
            .param("model", report.meta.model).param("promptVersion", report.meta.promptVersion)
            .param("rubricVersion", report.meta.rubricVersion)
            .param("setVersion", report.meta.questionSetVersion)
            .update()
    }

    fun report(interviewId: UUID): Report? =
        db.sql("select payload from report where interview_id = :id")
            .param("id", interviewId)
            .query { rs, _ -> json.report(rs.getString("payload")) }
            .optional().orElse(null)

    fun reportSummary(interviewId: UUID): Pair<String, Double>? =
        db.sql("select recommendation, overall_score from report where interview_id = :id")
            .param("id", interviewId)
            .query { rs, _ -> rs.getString("recommendation") to rs.getDouble("overall_score") }
            .optional().orElse(null)

    // ---------- мапперы ----------

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = InterviewRow(
        id = rs.uuid("id"),
        vacancyId = rs.uuid("vacancy_id"),
        questionSetId = rs.uuid("question_set_id"),
        questionSetVersion = rs.getInt("question_set_version"),
        candidateName = rs.getString("candidate_name"),
        resumeText = rs.getString("resume_text"),
        status = rs.enum("status"),
        candidateToken = rs.getString("candidate_token"),
        expiresAt = rs.instant("expires_at"),
        consentAt = rs.instantOrNull("consent_at"),
        createdAt = rs.instant("created_at"),
        completedAt = rs.instantOrNull("completed_at"),
        failureStage = rs.getString("failure_stage"),
        failureMessage = rs.getString("failure_message"),
        ttsVoice = rs.getString("tts_voice"),
    )

    private fun mapPlanItem(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = PlanItemRow(
        id = rs.uuid("id"),
        interviewId = rs.uuid("interview_id"),
        ord = rs.getInt("ord"),
        kind = rs.enum("kind"),
        origin = rs.enum("origin"),
        originQuestionId = rs.uuidOrNull("origin_question_id"),
        parentQuestionId = rs.uuidOrNull("parent_question_id"),
        text = rs.getString("text"),
        requirementId = rs.uuidOrNull("requirement_id"),
        strongSignals = json.strings(rs.getString("strong_signals")),
        audioKey = rs.getString("audio_key"),
    )

    private fun mapShare(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = ShareLinkRow(
        id = rs.uuid("id"),
        interviewId = rs.uuid("interview_id"),
        token = rs.getString("token"),
        expiresAt = rs.instant("expires_at"),
        revokedAt = rs.instantOrNull("revoked_at"),
    )

    private fun mapAntifraud(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = AntifraudRow(
        id = rs.uuid("id"),
        interviewId = rs.uuid("interview_id"),
        type = rs.enum("type"),
        occurredAt = rs.instant("occurred_at"),
    )
}
