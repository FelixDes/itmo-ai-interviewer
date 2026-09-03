package com.itmo.napoleonit.aiinterviewer.questions

import com.itmo.napoleonit.aiinterviewer.data.ReferenceData
import com.itmo.napoleonit.aiinterviewer.domain.RequirementRow
import com.itmo.napoleonit.aiinterviewer.domain.VacancyRow
import com.itmo.napoleonit.aiinterviewer.web.dto.QuestionOrigin
import com.itmo.napoleonit.aiinterviewer.web.dto.QuestionSetSource
import org.springframework.stereotype.Component

/**
 * Эталонные 6 вопросов заказчика (Р-14).
 *
 * Смысл не в демо: к этим вопросам у нас есть оценка эксперта на том же входе,
 * поэтому только на них можно сравнивать наш анализ с эталоном и отлаживать
 * оценку отдельно от генерации.
 */
@Component
class ReferenceQuestionGenerator : QuestionGenerator {

    override val source = QuestionSetSource.REFERENCE

    override fun generateCore(vacancy: VacancyRow, requirements: List<RequirementRow>) =
        ReferenceData.questions.map { q ->
            GeneratedQuestion(
                text = q.text,
                requirementId = requirements.find { it.text == q.requirementText }?.id,
                strongSignals = q.signals,
                origin = QuestionOrigin.VACANCY,
            )
        }

    override fun generatePersonal(
        vacancy: VacancyRow,
        requirements: List<RequirementRow>,
        resumeText: String,
    ) = ReferenceData.personalQuestions(resumeText).map { q ->
        GeneratedQuestion(q.text, null, q.signals, QuestionOrigin.RESUME)
    }
}

/** Заглушка на месте будущего LLM-генератора. Пока отдаёт тот же эталонный набор. */
@Component
class StubLlmQuestionGenerator(private val reference: ReferenceQuestionGenerator) : QuestionGenerator {

    override val source = QuestionSetSource.LLM

    override fun generateCore(vacancy: VacancyRow, requirements: List<RequirementRow>) =
        reference.generateCore(vacancy, requirements)

    override fun generatePersonal(
        vacancy: VacancyRow,
        requirements: List<RequirementRow>,
        resumeText: String,
    ) = reference.generatePersonal(vacancy, requirements, resumeText)
}
