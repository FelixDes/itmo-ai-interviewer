package com.itmo.napoleonit.aiinterviewer.persistence

import com.itmo.napoleonit.aiinterviewer.domain.*
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

@Repository
class AnswerRepository(private val db: JdbcClient, private val json: Json) {

    fun insert(row: AnswerRow) {
        db.sql(
            """
            insert into answer (id, interview_question_id, media_key, content_type, duration_ms, status, created_at)
            values (:id, :questionId, :mediaKey, :contentType, :durationMs, :status, :createdAt)
            """
        )
            .param("id", row.id).param("questionId", row.interviewQuestionId)
            .param("mediaKey", row.mediaKey).param("contentType", row.contentType)
            .param("durationMs", row.durationMs).param("status", row.state.name)
            .param("createdAt", row.createdAt.offset())
            .update()
    }

    fun find(id: UUID): AnswerRow? = db.sql("select * from answer where id = :id")
        .param("id", id).query(::map).optional().orElse(null)

    fun byQuestion(questionId: UUID): AnswerRow? =
        db.sql("select * from answer where interview_question_id = :id")
            .param("id", questionId).query(::map).optional().orElse(null)

    fun byInterview(interviewId: UUID): List<AnswerRow> =
        db.sql(
            """
            select a.* from answer a
            join interview_question q on q.id = a.interview_question_id
            where q.interview_id = :id
            order by q.ord
            """
        ).param("id", interviewId).query(::map).list()

    fun setState(id: UUID, state: AnswerState) {
        db.sql("update answer set status = :state where id = :id")
            .param("id", id).param("state", state.name).update()
    }

    fun complete(id: UUID, state: AnswerState, durationMs: Long?, at: Instant) {
        db.sql(
            """
            update answer set status = :state, duration_ms = coalesce(:durationMs, duration_ms), completed_at = :at
            where id = :id
            """
        )
            .param("id", id).param("state", state.name).param("durationMs", durationMs)
            .param("at", at.offset()).update()
    }

    // ---------- транскрипт ----------

    fun saveTranscript(row: TranscriptRow) {
        db.sql(
            """
            insert into transcript (id, answer_id, raw_text, refined_text, segments, asr_model)
            values (:id, :answerId, :raw, :refined, cast(:segments as jsonb), :model)
            on conflict (answer_id) do update set
                raw_text = excluded.raw_text,
                refined_text = excluded.refined_text,
                segments = excluded.segments,
                asr_model = excluded.asr_model
            """
        )
            .param("id", row.id).param("answerId", row.answerId)
            .param("raw", row.rawText).param("refined", row.refinedText)
            .param("segments", json.write(row.segments)).param("model", row.asrModel)
            .update()
    }

    fun transcript(answerId: UUID): TranscriptRow? =
        db.sql("select * from transcript where answer_id = :id")
            .param("id", answerId).query(::mapTranscript).optional().orElse(null)

    // ---------- оценка ответа ----------

    fun saveEvaluation(row: EvaluationRow) {
        db.sql(
            """
            insert into answer_evaluation (id, answer_id, scores, quotes, confidence, comment, model, prompt_version)
            values (:id, :answerId, cast(:scores as jsonb), cast(:quotes as jsonb),
                    :confidence, :comment, :model, :promptVersion)
            on conflict (answer_id) do update set
                scores = excluded.scores,
                quotes = excluded.quotes,
                confidence = excluded.confidence,
                comment = excluded.comment,
                model = excluded.model,
                prompt_version = excluded.prompt_version
            """
        )
            .param("id", row.id).param("answerId", row.answerId)
            .param("scores", json.write(row.scores)).param("quotes", json.write(row.quotes))
            .param("confidence", row.confidence?.name).param("comment", row.comment)
            .param("model", row.model).param("promptVersion", row.promptVersion)
            .update()
    }

    fun evaluation(answerId: UUID): EvaluationRow? =
        db.sql("select * from answer_evaluation where answer_id = :id")
            .param("id", answerId).query(::mapEvaluation).optional().orElse(null)

    // ---------- фоновые задачи ----------

    fun upsertJob(row: ProcessingJobRow) {
        db.sql(
            """
            insert into processing_job (id, kind, state, stage, interview_id, answer_id, attempts, last_error)
            values (:id, :kind, :state, :stage, :interviewId, :answerId, :attempts, :error)
            on conflict (id) do update set
                state = excluded.state, stage = excluded.stage,
                attempts = excluded.attempts, last_error = excluded.last_error,
                updated_at = now()
            """
        )
            .param("id", row.id).param("kind", row.kind.name).param("state", row.state.name)
            .param("stage", row.stage?.name).param("interviewId", row.interviewId)
            .param("answerId", row.answerId).param("attempts", row.attempts).param("error", row.lastError)
            .update()
    }

    /** Активная задача по интервью — источник поля processing в CandidateState. */
    fun runningJob(interviewId: UUID): ProcessingJobRow? =
        db.sql(
            """
            select * from processing_job
            where interview_id = :id and state in ('PENDING', 'RUNNING')
            order by created_at desc limit 1
            """
        ).param("id", interviewId).query(::mapJob).optional().orElse(null)

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = AnswerRow(
        id = rs.uuid("id"),
        interviewQuestionId = rs.uuid("interview_question_id"),
        mediaKey = rs.getString("media_key"),
        contentType = rs.getString("content_type"),
        durationMs = rs.longOrNull("duration_ms"),
        state = rs.enum("status"),
        createdAt = rs.instant("created_at"),
        completedAt = rs.instantOrNull("completed_at"),
    )

    private fun mapTranscript(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = TranscriptRow(
        id = rs.uuid("id"),
        answerId = rs.uuid("answer_id"),
        rawText = rs.getString("raw_text"),
        refinedText = rs.getString("refined_text"),
        segments = json.segments(rs.getString("segments")),
        asrModel = rs.getString("asr_model"),
    )

    private fun mapEvaluation(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = EvaluationRow(
        id = rs.uuid("id"),
        answerId = rs.uuid("answer_id"),
        scores = json.scores(rs.getString("scores")),
        quotes = json.quotes(rs.getString("quotes")),
        confidence = rs.enumOrNull("confidence"),
        comment = rs.getString("comment"),
        model = rs.getString("model"),
        promptVersion = rs.getString("prompt_version"),
    )

    private fun mapJob(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = ProcessingJobRow(
        id = rs.uuid("id"),
        kind = rs.enum("kind"),
        state = rs.enum("state"),
        stage = rs.enumOrNull("stage"),
        interviewId = rs.uuidOrNull("interview_id"),
        answerId = rs.uuidOrNull("answer_id"),
        attempts = rs.getInt("attempts"),
        lastError = rs.getString("last_error"),
    )
}
