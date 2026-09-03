package com.itmo.napoleonit.aiinterviewer.stub

import com.itmo.napoleonit.aiinterviewer.media.S3Service
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import java.time.Instant

/**
 * Правдоподобная карточка результата для разработки фронта.
 * Форма соответствует docs/api.md §3 и эталону заказчика
 * («Пример_детализации_и_анализа_видео_интервью.pdf»).
 */
object StubReportFactory {

    private val TRANSCRIPT_RAW = "Использовали кафку на продакшене, там сообщения идут, дублирование " +
        "настроено девопсами, у каждого сообщения есть гуид, плюс паттерн аутбокс, " +
        "мы гарантируем что сообщение отправится."

    private val TRANSCRIPT_REFINED = "Использовали Kafka на продакшене, там сообщения идут, дублирование " +
        "настроено DevOps, у каждого сообщения есть GUID, плюс паттерн Outbox, " +
        "мы гарантируем, что сообщение отправится."

    fun build(session: InterviewSession, store: StubStore, s3: S3Service): Report {
        val vacancy = store.vacancy(session.vacancyId)
        val answers = session.plan.map { item -> answerReport(item, s3) }

        val evaluated = answers.count { it.status == AnswerStatus.EVALUATED }
        val skipped = answers.count { it.status == AnswerStatus.SKIPPED }

        val verdicts = vacancy.requirements.mapIndexed { index, r -> verdict(r, index, session) }

        return Report(
            interviewId = session.id,
            candidateName = session.candidateName,
            vacancyTitle = vacancy.title,
            vacancyGrade = vacancy.grade,
            completedAt = session.completedAt ?: Instant.now(),
            recommendation = Recommendation.NEEDS_CHECK,
            overallScore = 6.7,
            confidence = Confidence.MEDIUM,
            summary = "Кандидат показал общее понимание ключевых технологий вакансии: очереди сообщений, " +
                "PostgreSQL, CI/CD и асинхронный Python. Ответы содержат практический опыт, но часто " +
                "не хватает конкретных примеров, метрик и описания личного вклада, поэтому глубину " +
                "компетенций на заявленном уровне подтвердить нельзя. Рекомендуется дополнительная " +
                "техническая проверка на следующем этапе.",
            requirementsMust = verdicts.filter { it.kind == RequirementKind.MUST },
            requirementsNice = verdicts.filter { it.kind == RequirementKind.NICE },
            answers = answers,
            strengths = listOf(
                Finding(
                    "Понимает гарантии доставки в Kafka: называет Outbox и дедупликацию по идентификатору.",
                    FindingType.FACT,
                    evidenceOf(answers, 2, "плюс паттерн Outbox, мы гарантируем, что сообщение отправится"),
                ),
                Finding(
                    "Знает инструменты диагностики медленных запросов в PostgreSQL, упоминает EXPLAIN ANALYZE.",
                    FindingType.FACT,
                    evidenceOf(answers, 3, "всегда мы смотрим на explain"),
                ),
                Finding(
                    "Судя по формулировкам, имеет реальный продакшн-опыт, а не только теоретический.",
                    FindingType.INFERENCE,
                ),
            ),
            risks = listOf(
                Finding(
                    "Не раскрыт личный вклад: описывает работу команды, а не свои решения.",
                    FindingType.FACT,
                    evidenceOf(answers, 2, "дублирование настроено DevOps"),
                ),
                Finding(
                    "Нет масштаба и метрик: объёмы данных и нагрузка названы приблизительно.",
                    FindingType.FACT,
                ),
                Finding(
                    "Возможен разрыв между заявленным в резюме уровнем и глубиной ответов. Требует проверки.",
                    FindingType.ASSUMPTION,
                ),
            ),
            skillsFound = listOf(
                "Python", "Apache Kafka", "PostgreSQL", "CI/CD", "Docker", "FastAPI", "Django", "ETL", "asyncio",
            ),
            skillsNotChecked = listOf(
                "ClickHouse", "Kubernetes", "Prometheus и Grafana", "gRPC", "Redis Cluster",
                "Участие в code review — этим интервью не проверяется",
            ),
            nextStageQuestions = listOf(
                "Какие именно решения по надёжности доставки принимали вы, а какие — команда DevOps?",
                "Назовите конкретные объёмы: сообщений в секунду, размер таблиц, время загрузки.",
                "Опыт с ClickHouse и Kubernetes в интервью не подтверждён — проверить отдельно.",
            ),
            candidateFeedback = "По итогам интервью видно, что у вас хорошая техническая база и практический " +
                "опыт работы с очередями сообщений, большими объёмами данных и асинхронным программированием.\n\n" +
                "При этом хотелось бы видеть больше конкретики о результатах вашей работы: какие задачи удалось " +
                "решить, что изменилось после вашего участия и какие метрики это подтверждают. Также стоит " +
                "подробнее раскрывать именно свой вклад в проекты — за какие решения вы отвечали лично.\n\n" +
                "Более конкретные примеры в этих областях помогут полнее показать ваш практический опыт.",
            technical = TechnicalBlock(
                antifraudEvents = session.antifraud.toList(),
                unrateableAnswers = answers.count { it.status == AnswerStatus.UNRATEABLE },
                failedAnswers = answers.count { it.status == AnswerStatus.FAILED },
                notes = buildList {
                    add("Оценено ответов: $evaluated из ${answers.size}")
                    if (skipped > 0) add("Кандидат пропустил вопросов: $skipped")
                    add("ВНИМАНИЕ: карточка сгенерирована заглушкой скелета, это не результат работы модели")
                },
            ),
            meta = ReportMeta(
                model = "stub",
                promptVersion = "stub-0",
                rubricVersion = "stub-0",
                questionSetVersion = session.questionSetVersion,
                generatedAt = Instant.now(),
            ),
        )
    }

    private fun answerReport(item: PlanItem, s3: S3Service): AnswerReport {
        val answer = item.answer
        val status = answer?.status ?: AnswerStatus.FAILED
        val evaluated = status == AnswerStatus.EVALUATED
        return AnswerReport(
            answerId = answer?.id,
            questionId = item.questionId,
            ord = item.ord,
            kind = item.kind,
            questionText = item.text,
            requirementId = item.requirementId,
            origin = item.origin,
            parentQuestionId = item.parentQuestionId,
            status = status,
            videoUrl = answer?.mediaKey?.let { s3.presignDownload(it) },
            durationMs = answer?.durationMs,
            transcriptRefined = if (evaluated) TRANSCRIPT_REFINED else null,
            transcriptRaw = if (evaluated) TRANSCRIPT_RAW else null,
            scores = if (evaluated) Scores(4, 3, 4, 3, 2, 2) else null,
            confidence = if (evaluated) Confidence.MEDIUM else null,
            comment = when (status) {
                AnswerStatus.EVALUATED -> "Ответ по существу, механизмы названы верно, " +
                    "но без конкретного проекта и без личного вклада."
                AnswerStatus.SKIPPED -> "Кандидат пропустил вопрос. Компетенция не проверена."
                AnswerStatus.UNRATEABLE -> "Качество записи не позволяет уверенно оценить ответ."
                AnswerStatus.FAILED -> "Ответ не был обработан из-за технической ошибки."
            },
            evidence = if (evaluated) {
                listOf(Evidence(answer?.id, item.ord, "плюс паттерн Outbox", 12_400, 15_100))
            } else emptyList(),
        )
    }

    private fun evidenceOf(answers: List<AnswerReport>, ord: Int, quote: String): List<Evidence> {
        val target = answers.find { it.ord == ord } ?: return emptyList()
        return listOf(Evidence(target.answerId, target.ord, quote, 12_400, 15_100))
    }

    private fun verdict(r: Requirement, index: Int, session: InterviewSession): RequirementVerdict {
        val status = when {
            r.notVerifiable -> RequirementStatus.NOT_CHECKED
            index % 4 == 0 -> RequirementStatus.CONFIRMED
            index % 4 == 1 -> RequirementStatus.PARTIAL
            index % 4 == 2 -> RequirementStatus.NOT_CHECKED
            else -> RequirementStatus.NOT_CONFIRMED
        }
        val basis = when (status) {
            RequirementStatus.CONFIRMED, RequirementStatus.PARTIAL -> EvidenceBasis.ANSWER
            RequirementStatus.NOT_CONFIRMED -> if (session.resumeText != null) EvidenceBasis.RESUME else EvidenceBasis.NONE
            RequirementStatus.NOT_CHECKED -> EvidenceBasis.NONE
        }
        return RequirementVerdict(
            requirementId = r.id,
            text = r.text,
            kind = r.kind,
            weight = r.weight,
            stopFactor = r.stopFactor,
            status = status,
            basis = basis,
            comment = when (status) {
                RequirementStatus.CONFIRMED -> "Подтверждено ответом кандидата."
                RequirementStatus.PARTIAL -> "Упомянуто, но без глубины и без личного вклада."
                RequirementStatus.NOT_CONFIRMED -> "Заявлено в резюме, ответом не подтверждено."
                RequirementStatus.NOT_CHECKED -> "Этим интервью не проверяется."
            },
        )
    }
}
