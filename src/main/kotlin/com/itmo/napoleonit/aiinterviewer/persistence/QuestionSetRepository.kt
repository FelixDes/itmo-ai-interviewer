package com.itmo.napoleonit.aiinterviewer.persistence

import com.itmo.napoleonit.aiinterviewer.domain.QuestionRow
import com.itmo.napoleonit.aiinterviewer.domain.QuestionSetRow
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class QuestionSetRepository(private val db: JdbcClient, private val json: Json) {

    fun insert(row: QuestionSetRow) {
        db.sql(
            """
            insert into question_set (id, vacancy_id, version, source, frozen, frozen_at, created_at)
            values (:id, :vacancyId, :version, :source, :frozen, :frozenAt, :createdAt)
            """
        )
            .param("id", row.id).param("vacancyId", row.vacancyId).param("version", row.version)
            .param("source", row.source.name).param("frozen", row.frozen)
            .param("frozenAt", row.frozenAt.offsetOrNull()).param("createdAt", row.createdAt.offset())
            .update()
    }

    fun freeze(id: UUID, at: java.time.Instant) {
        db.sql("update question_set set frozen = true, frozen_at = :at where id = :id")
            .param("id", id).param("at", at.offset()).update()
    }

    fun find(id: UUID): QuestionSetRow? = db.sql("select * from question_set where id = :id")
        .param("id", id).query(::mapSet).optional().orElse(null)

    fun byVacancy(vacancyId: UUID): List<QuestionSetRow> =
        db.sql("select * from question_set where vacancy_id = :id order by version desc")
            .param("id", vacancyId).query(::mapSet).list()

    fun activeSet(vacancyId: UUID): QuestionSetRow? =
        db.sql("select * from question_set where vacancy_id = :id and frozen order by version desc limit 1")
            .param("id", vacancyId).query(::mapSet).optional().orElse(null)

    fun draftSet(vacancyId: UUID): QuestionSetRow? =
        db.sql("select * from question_set where vacancy_id = :id and not frozen order by version desc limit 1")
            .param("id", vacancyId).query(::mapSet).optional().orElse(null)

    fun nextVersion(vacancyId: UUID): Int =
        db.sql("select coalesce(max(version), 0) + 1 from question_set where vacancy_id = :id")
            .param("id", vacancyId).query(Int::class.java).single()

    // ---------- вопросы ----------

    fun questions(setId: UUID): List<QuestionRow> =
        db.sql("select * from question where question_set_id = :id order by ord")
            .param("id", setId).query(::mapQuestion).list()

    fun replaceQuestions(setId: UUID, questions: List<QuestionRow>) {
        db.sql("delete from question where question_set_id = :id").param("id", setId).update()
        questions.forEach { q ->
            db.sql(
                """
                insert into question (id, question_set_id, ord, text, requirement_id, strong_signals, origin)
                values (:id, :setId, :ord, :text, :requirementId, cast(:signals as jsonb), :origin)
                """
            )
                .param("id", q.id).param("setId", setId).param("ord", q.ord).param("text", q.text)
                .param("requirementId", q.requirementId)
                .param("signals", json.write(q.strongSignals))
                .param("origin", q.origin.name)
                .update()
        }
    }

    private fun mapSet(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = QuestionSetRow(
        id = rs.uuid("id"),
        vacancyId = rs.uuid("vacancy_id"),
        version = rs.getInt("version"),
        source = rs.enum("source"),
        frozen = rs.getBoolean("frozen"),
        frozenAt = rs.instantOrNull("frozen_at"),
        createdAt = rs.instant("created_at"),
    )

    private fun mapQuestion(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = QuestionRow(
        id = rs.uuid("id"),
        questionSetId = rs.uuid("question_set_id"),
        ord = rs.getInt("ord"),
        text = rs.getString("text"),
        requirementId = rs.uuidOrNull("requirement_id"),
        strongSignals = json.strings(rs.getString("strong_signals")),
        origin = rs.enum("origin"),
    )
}
