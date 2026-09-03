package com.itmo.napoleonit.aiinterviewer.persistence

import com.itmo.napoleonit.aiinterviewer.domain.*
import com.itmo.napoleonit.aiinterviewer.web.dto.Grade
import com.itmo.napoleonit.aiinterviewer.web.dto.RequirementKind
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class VacancyRepository(private val db: JdbcClient) {

    fun insert(row: VacancyRow) {
        db.sql(
            """
            insert into vacancy (id, owner_username, title, grade, description, created_at)
            values (:id, :owner, :title, :grade, :description, :createdAt)
            """
        )
            .param("id", row.id)
            .param("owner", row.ownerUsername)
            .param("title", row.title)
            .param("grade", row.grade.name)
            .param("description", row.description)
            .param("createdAt", row.createdAt.offset())
            .update()
    }

    fun update(id: UUID, title: String, grade: Grade, description: String) {
        db.sql("update vacancy set title = :title, grade = :grade, description = :description where id = :id")
            .param("id", id).param("title", title).param("grade", grade.name).param("description", description)
            .update()
    }

    fun find(id: UUID): VacancyRow? = db.sql("select * from vacancy where id = :id")
        .param("id", id).query(::mapVacancy).optional().orElse(null)

    fun findByOwner(owner: String): List<VacancyRow> =
        db.sql("select * from vacancy where owner_username = :owner order by created_at desc")
            .param("owner", owner).query(::mapVacancy).list()

    fun findByTitle(title: String): VacancyRow? =
        db.sql("select * from vacancy where title = :title order by created_at limit 1")
            .param("title", title).query(::mapVacancy).optional().orElse(null)

    // ---------- требования ----------

    fun requirements(vacancyId: UUID, includeDeleted: Boolean = false): List<RequirementRow> =
        db.sql(
            "select * from requirement where vacancy_id = :id" +
                (if (includeDeleted) "" else " and not deleted") + " order by ord"
        ).param("id", vacancyId).query(::mapRequirement).list()

    fun requirementsByIds(ids: Collection<UUID>): List<RequirementRow> {
        if (ids.isEmpty()) return emptyList()
        return db.sql("select * from requirement where id in (:ids)")
            .param("ids", ids.toList()).query(::mapRequirement).list()
    }

    fun insertRequirement(row: RequirementRow) {
        db.sql(
            """
            insert into requirement (id, vacancy_id, ord, text, kind, weight, stop_factor, not_verifiable, deleted)
            values (:id, :vacancyId, :ord, :text, :kind, :weight, :stopFactor, :notVerifiable, :deleted)
            """
        )
            .param("id", row.id).param("vacancyId", row.vacancyId).param("ord", row.ord)
            .param("text", row.text).param("kind", row.kind.name).param("weight", row.weight)
            .param("stopFactor", row.stopFactor).param("notVerifiable", row.notVerifiable)
            .param("deleted", row.deleted)
            .update()
    }

    fun updateRequirement(row: RequirementRow) {
        db.sql(
            """
            update requirement set ord = :ord, text = :text, kind = :kind, weight = :weight,
                   stop_factor = :stopFactor, not_verifiable = :notVerifiable, deleted = false
            where id = :id
            """
        )
            .param("id", row.id).param("ord", row.ord).param("text", row.text)
            .param("kind", row.kind.name).param("weight", row.weight)
            .param("stopFactor", row.stopFactor).param("notVerifiable", row.notVerifiable)
            .update()
    }

    /** Мягкое удаление: на требование ссылаются вопросы и старые карточки. */
    fun softDeleteRequirementsExcept(vacancyId: UUID, keep: Collection<UUID>) {
        val sql = StringBuilder("update requirement set deleted = true where vacancy_id = :vacancyId")
        if (keep.isNotEmpty()) sql.append(" and id not in (:keep)")
        val spec = db.sql(sql.toString()).param("vacancyId", vacancyId)
        if (keep.isNotEmpty()) spec.param("keep", keep.toList())
        spec.update()
    }

    private fun mapVacancy(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = VacancyRow(
        id = rs.uuid("id"),
        ownerUsername = rs.getString("owner_username"),
        title = rs.getString("title"),
        grade = rs.enum("grade"),
        description = rs.getString("description"),
        createdAt = rs.instant("created_at"),
    )

    private fun mapRequirement(rs: java.sql.ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = RequirementRow(
        id = rs.uuid("id"),
        vacancyId = rs.uuid("vacancy_id"),
        ord = rs.getInt("ord"),
        text = rs.getString("text"),
        kind = rs.enum<RequirementKind>("kind"),
        weight = rs.getInt("weight"),
        stopFactor = rs.getBoolean("stop_factor"),
        notVerifiable = rs.getBoolean("not_verifiable"),
        deleted = rs.getBoolean("deleted"),
    )
}
