package com.itmo.napoleonit.aiinterviewer.service

import com.itmo.napoleonit.aiinterviewer.data.ReferenceData
import com.itmo.napoleonit.aiinterviewer.domain.*
import com.itmo.napoleonit.aiinterviewer.persistence.*
import com.itmo.napoleonit.aiinterviewer.questions.QuestionGenerators
import com.itmo.napoleonit.aiinterviewer.web.*
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class VacancyService(
    private val vacancies: VacancyRepository,
    private val sets: QuestionSetRepository,
    private val interviews: InterviewRepository,
    private val generators: QuestionGenerators,
) {

    // ---------- вакансии ----------

    fun list(owner: String): List<VacancyListItem> = vacancies.findByOwner(owner).map { v ->
        VacancyListItem(
            id = v.id,
            title = v.title,
            grade = v.grade,
            interviewCount = interviews.countByVacancy(v.id),
            hasFrozenQuestionSet = sets.activeSet(v.id) != null,
            createdAt = v.createdAt,
        )
    }

    @Transactional
    fun create(owner: String, input: VacancyInput): Vacancy {
        validate(input)
        val row = VacancyRow(
            id = UUID.randomUUID(),
            ownerUsername = owner,
            title = input.title,
            grade = input.grade,
            description = input.description,
            createdAt = Instant.now(),
        )
        vacancies.insert(row)
        input.requirements.forEachIndexed { index, r ->
            vacancies.insertRequirement(
                RequirementRow(
                    id = UUID.randomUUID(), vacancyId = row.id, ord = index + 1, text = r.text,
                    kind = r.kind, weight = r.weight, stopFactor = r.stopFactor,
                    notVerifiable = r.notVerifiable, deleted = false,
                )
            )
        }
        return toDto(row)
    }

    fun get(owner: String, id: UUID): Vacancy = toDto(requireOwned(owner, id))

    @Transactional
    fun update(owner: String, id: UUID, input: VacancyInput): Vacancy {
        validate(input)
        val row = requireOwned(owner, id)
        vacancies.update(id, input.title, input.grade, input.description)

        val existing = vacancies.requirements(id, includeDeleted = true).associateBy { it.id }
        val kept = mutableListOf<UUID>()
        input.requirements.forEachIndexed { index, r ->
            val requirementId = r.id?.takeIf { existing.containsKey(it) } ?: UUID.randomUUID()
            val updated = RequirementRow(
                id = requirementId, vacancyId = id, ord = index + 1, text = r.text, kind = r.kind,
                weight = r.weight, stopFactor = r.stopFactor, notVerifiable = r.notVerifiable, deleted = false,
            )
            if (existing.containsKey(requirementId)) vacancies.updateRequirement(updated)
            else vacancies.insertRequirement(updated)
            kept += requirementId
        }
        // Мягкое удаление: на требования ссылаются вопросы и уже собранные карточки
        vacancies.softDeleteRequirementsExcept(id, kept)

        return toDto(row.copy(title = input.title, grade = input.grade, description = input.description))
    }

    // ---------- наборы вопросов ----------

    fun listSets(owner: String, vacancyId: UUID): List<QuestionSet> {
        requireOwned(owner, vacancyId)
        return sets.byVacancy(vacancyId).map { toDto(it) }
    }

    @Transactional
    fun generateSet(owner: String, vacancyId: UUID, source: QuestionSetSource): QuestionSet {
        val vacancy = requireOwned(owner, vacancyId)
        val requirements = vacancies.requirements(vacancyId)
        val generated = generators.of(source).generateCore(vacancy, requirements)

        val setRow = QuestionSetRow(
            id = UUID.randomUUID(), vacancyId = vacancyId, version = sets.nextVersion(vacancyId),
            source = source, frozen = false, frozenAt = null, createdAt = Instant.now(),
        )
        sets.insert(setRow)
        sets.replaceQuestions(
            setRow.id,
            generated.mapIndexed { index, q ->
                QuestionRow(UUID.randomUUID(), setRow.id, index + 1, q.text, q.requirementId, q.strongSignals, q.origin)
            }
        )
        return toDto(setRow)
    }

    fun getSet(owner: String, id: UUID): QuestionSet {
        val set = sets.find(id) ?: throw NotFound("Набор вопросов не найден")
        requireOwned(owner, set.vacancyId)
        return toDto(set)
    }

    @Transactional
    fun updateSet(owner: String, id: UUID, questions: List<QuestionInput>): QuestionSet {
        val set = sets.find(id) ?: throw NotFound("Набор вопросов не найден")
        requireOwned(owner, set.vacancyId)
        if (set.frozen) {
            throw Conflict("QUESTION_SET_FROZEN", "Набор зафиксирован, создайте новую версию через revise")
        }
        if (questions.isEmpty()) throw ValidationFailed(mapOf("questions" to "нужен хотя бы один вопрос"))
        if (questions.any { it.text.isBlank() }) {
            throw ValidationFailed(mapOf("questions" to "текст вопроса не должен быть пустым"))
        }
        // ord задаётся порядком массива, сервер его и присваивает
        sets.replaceQuestions(
            id,
            questions.mapIndexed { index, q ->
                QuestionRow(
                    id = q.id ?: UUID.randomUUID(), questionSetId = id, ord = index + 1, text = q.text,
                    requirementId = q.requirementId, strongSignals = q.strongSignals,
                    origin = QuestionOrigin.VACANCY,
                )
            }
        )
        return toDto(set)
    }

    /** Правка зафиксированного набора запрещена — вместо неё новая версия (Р-13). */
    @Transactional
    fun reviseSet(owner: String, id: UUID): QuestionSet {
        val set = sets.find(id) ?: throw NotFound("Набор вопросов не найден")
        requireOwned(owner, set.vacancyId)
        val draft = QuestionSetRow(
            id = UUID.randomUUID(), vacancyId = set.vacancyId, version = sets.nextVersion(set.vacancyId),
            source = set.source, frozen = false, frozenAt = null, createdAt = Instant.now(),
        )
        sets.insert(draft)
        sets.replaceQuestions(
            draft.id,
            sets.questions(id).map { it.copy(id = UUID.randomUUID(), questionSetId = draft.id) }
        )
        return toDto(draft)
    }

    @Transactional
    fun freezeSet(owner: String, id: UUID): QuestionSet {
        val set = sets.find(id) ?: throw NotFound("Набор вопросов не найден")
        requireOwned(owner, set.vacancyId)
        if (set.frozen) return toDto(set)
        if (sets.questions(id).isEmpty()) {
            throw Conflict("INVALID_STATE", "Нельзя зафиксировать пустой набор вопросов")
        }
        val at = Instant.now()
        sets.freeze(id, at)
        return toDto(set.copy(frozen = true, frozenAt = at))
    }

    // ---------- демо-данные ----------

    @Transactional
    fun demoSeed(owner: String): DemoSeedResult {
        vacancies.findByTitle(ReferenceData.VACANCY_TITLE)?.let { existing ->
            val set = sets.activeSet(existing.id)
            if (set != null) return DemoSeedResult(existing.id, set.id)
        }
        val vacancy = create(
            owner,
            VacancyInput(
                title = ReferenceData.VACANCY_TITLE,
                grade = Grade.MIDDLE_PLUS,
                description = ReferenceData.VACANCY_DESCRIPTION,
                requirements = ReferenceData.requirements,
            )
        )
        val draft = generateSet(owner, vacancy.id, QuestionSetSource.REFERENCE)
        val frozen = freezeSet(owner, draft.id)
        return DemoSeedResult(vacancy.id, frozen.id)
    }

    // ---------- вспомогательное ----------

    fun requireOwned(owner: String, vacancyId: UUID): VacancyRow {
        val vacancy = vacancies.find(vacancyId) ?: throw NotFound("Вакансия не найдена")
        if (vacancy.ownerUsername != owner) throw Forbidden(message = "Вакансия другого рекрутера")
        return vacancy
    }

    private fun toDto(row: VacancyRow) = Vacancy(
        id = row.id,
        title = row.title,
        grade = row.grade,
        description = row.description,
        requirements = vacancies.requirements(row.id).map { it.toDto() },
        activeQuestionSet = sets.activeSet(row.id)?.let { ref(it) },
        draftQuestionSet = sets.draftSet(row.id)?.let { ref(it) },
        createdAt = row.createdAt,
    )

    private fun ref(set: QuestionSetRow) =
        QuestionSetRef(set.id, set.version, set.frozen, sets.questions(set.id).size)

    private fun toDto(set: QuestionSetRow) = QuestionSet(
        id = set.id,
        vacancyId = set.vacancyId,
        version = set.version,
        source = set.source,
        frozen = set.frozen,
        frozenAt = set.frozenAt,
        questions = sets.questions(set.id).map { it.toDto() },
        createdAt = set.createdAt,
    )

    private fun validate(input: VacancyInput) {
        val errors = buildMap {
            if (input.title.isBlank()) put("title", "не должно быть пустым")
            if (input.requirements.isEmpty()) put("requirements", "нужно хотя бы одно требование")
            input.requirements.forEachIndexed { i, r ->
                if (r.text.isBlank()) put("requirements[$i].text", "не должно быть пустым")
                if (r.weight !in 1..3) put("requirements[$i].weight", "должен быть от 1 до 3")
            }
        }
        if (errors.isNotEmpty()) throw ValidationFailed(errors)
    }
}
