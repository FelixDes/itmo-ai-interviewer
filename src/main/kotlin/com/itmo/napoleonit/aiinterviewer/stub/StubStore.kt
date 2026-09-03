package com.itmo.napoleonit.aiinterviewer.stub

import com.itmo.napoleonit.aiinterviewer.web.NotFound
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class StubStore {
    val vacancies = ConcurrentHashMap<UUID, Vacancy>()
    val questionSets = ConcurrentHashMap<UUID, QuestionSet>()
    val interviews = ConcurrentHashMap<UUID, InterviewSession>()

    /** owner-username -> id вакансий; разграничение доступа рекрутеров */
    val vacancyOwner = ConcurrentHashMap<UUID, String>()

    fun vacancy(id: UUID): Vacancy = vacancies[id] ?: throw NotFound("Вакансия не найдена")

    fun questionSet(id: UUID): QuestionSet = questionSets[id] ?: throw NotFound("Набор вопросов не найден")

    fun interview(id: UUID): InterviewSession = interviews[id] ?: throw NotFound("Интервью не найдено")

    fun byCandidateToken(token: String): InterviewSession =
        interviews.values.find { it.candidateToken == token } ?: throw NotFound("Ссылка не найдена")

    fun byShareToken(token: String): InterviewSession =
        interviews.values.find { it.share?.token == token } ?: throw NotFound("Ссылка не найдена")

    fun requirementsOf(vacancyId: UUID): List<Requirement> = vacancies[vacancyId]?.requirements ?: emptyList()

    fun setsOf(vacancyId: UUID): List<QuestionSet> =
        questionSets.values.filter { it.vacancyId == vacancyId }.sortedByDescending { it.version }

    fun activeSet(vacancyId: UUID): QuestionSet? = setsOf(vacancyId).firstOrNull { it.frozen }

    fun draftSet(vacancyId: UUID): QuestionSet? = setsOf(vacancyId).firstOrNull { !it.frozen }

    fun interviewsOf(vacancyId: UUID?): List<InterviewSession> =
        interviews.values
            .filter { vacancyId == null || it.vacancyId == vacancyId }
            .sortedByDescending { it.createdAt }
}
