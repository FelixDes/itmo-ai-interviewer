package com.itmo.napoleonit.aiinterviewer.questions

import com.itmo.napoleonit.aiinterviewer.domain.RequirementRow
import com.itmo.napoleonit.aiinterviewer.domain.VacancyRow
import com.itmo.napoleonit.aiinterviewer.web.dto.QuestionOrigin
import com.itmo.napoleonit.aiinterviewer.web.dto.QuestionSetSource
import java.util.UUID

/**
 * Генерация вопросов за интерфейсом (Р-7).
 *
 * У каждого вопроса обязательны привязка к компетенции, признаки сильного ответа
 * и основание генерации — требование Рамки §4, на нём держится объяснимость.
 */
data class GeneratedQuestion(
    val text: String,
    val requirementId: UUID?,
    val strongSignals: List<String>,
    val origin: QuestionOrigin,
)

interface QuestionGenerator {
    val source: QuestionSetSource

    /** Обязательное ядро: единое для всех кандидатов вакансии, обеспечивает сопоставимость. */
    fun generateCore(vacancy: VacancyRow, requirements: List<RequirementRow>): List<GeneratedQuestion>

    /** Персональные вопросы по резюме (Р-12): генерятся автоматически, рекрутер их не правит. */
    fun generatePersonal(
        vacancy: VacancyRow,
        requirements: List<RequirementRow>,
        resumeText: String,
    ): List<GeneratedQuestion>
}

@org.springframework.stereotype.Component
class QuestionGenerators(generators: List<QuestionGenerator>) {
    private val bySource = generators.associateBy { it.source }
    fun of(source: QuestionSetSource): QuestionGenerator =
        bySource[source] ?: error("Нет генератора вопросов для источника $source")
}
