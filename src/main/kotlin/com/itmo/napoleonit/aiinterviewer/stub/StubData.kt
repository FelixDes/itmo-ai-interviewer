package com.itmo.napoleonit.aiinterviewer.stub

import com.itmo.napoleonit.aiinterviewer.web.dto.*
import java.util.UUID

/**
 * Эталонные данные заказчика: вакансия Middle+ Python и 6 вопросов
 * из «Пример вакансии и вопросов.pdf». Используются демо-сидом и
 * реализацией REFERENCE (Р-14).
 */
object StubData {

    data class RefQuestion(val text: String, val requirementText: String?, val signals: List<String>)

    val requirements: List<RequirementInput> = listOf(
        req("Python — опыт коммерческой разработки от 5 лет", RequirementKind.MUST, 3),
        req("Django/FastAPI — разработка REST API", RequirementKind.MUST, 3),
        req("Микросервисная архитектура — проектирование и поддержка", RequirementKind.MUST, 3),
        req("Apache Kafka — продакшн-опыт с producers/consumers", RequirementKind.MUST, 3),
        req("PostgreSQL — проектирование схем, оптимизация запросов", RequirementKind.MUST, 3),
        req("ETL-процессы — extract, transform, load", RequirementKind.MUST, 2),
        req("Docker/Kubernetes — контейнеризация и оркестрация", RequirementKind.MUST, 2),
        req("ClickHouse", RequirementKind.MUST, 1),
        req("Event-driven архитектура, Event Sourcing и CQRS", RequirementKind.MUST, 2),
        req("API Gateway — интеграция через шлюзы", RequirementKind.MUST, 1),
        req("Мониторинг: Prometheus, Grafana или аналоги", RequirementKind.MUST, 2),
        req("Git, CI/CD — DevOps-практики", RequirementKind.MUST, 2),
        req("gRPC для межсервисного взаимодействия", RequirementKind.NICE, 1),
        req("ELK Stack (Elasticsearch, Logstash, Kibana)", RequirementKind.NICE, 1),
        req("Redis Cluster для высокодоступного кэширования", RequirementKind.NICE, 1),
        req("Domain-Driven Design", RequirementKind.NICE, 1),
        req("Performance tuning Python-приложений", RequirementKind.NICE, 2),
        // Рамка §3: навыки, которые этим интервью проверить нельзя
        req("Участие в code review", RequirementKind.NICE, 1, notVerifiable = true),
    )

    val questions: List<RefQuestion> = listOf(
        RefQuestion(
            "Расскажите, почему вы сейчас находитесь в поиске работы и что для себя ищете? " +
                "Каким проектом и какими задачами хотелось бы заниматься?",
            null,
            listOf("Внятная мотивация", "Совпадение ожиданий с задачами вакансии"),
        ),
        RefQuestion(
            "Расскажите про свой продакшн-сервис на Python с очередью — Kafka или RabbitMQ. " +
                "Какие библиотеки, как добивались, чтобы сообщение не потерялось и не обработалось дважды, " +
                "и что делали с необработанными сообщениями?",
            "Apache Kafka — продакшн-опыт с producers/consumers",
            listOf("Идемпотентность обработки", "Outbox или дедупликация по ключу", "Retry и dead-letter очередь"),
        ),
        RefQuestion(
            "Как вы загружали большие объёмы данных в PostgreSQL? Назовите объёмы, " +
                "как была устроена загрузка и как находили причину медленных запросов?",
            "PostgreSQL — проектирование схем, оптимизация запросов",
            listOf("Батчевая вставка или COPY", "EXPLAIN ANALYZE", "Конкретные объёмы данных"),
        ),
        RefQuestion(
            "Расскажите про CI/CD-пайплайн для Python-сервиса в контейнерах, который настраивали сами. " +
                "Что делал пайплайн, как собирали Docker-образ, как деплоили и откатывались?",
            "Git, CI/CD — DevOps-практики",
            listOf("Личный вклад в настройку", "Сборка образа и registry", "Внятная стратегия отката"),
        ),
        RefQuestion(
            "Когда выбираете асинхронный код, а когда синхронный? Опишите на примере из своего проекта. " +
                "И что будет, если внутри асинхронного кода вызвать блокирующую функцию?",
            "Python — опыт коммерческой разработки от 5 лет",
            listOf("IO-bound против CPU-bound", "Блокировка событийного цикла", "Пример из своего проекта"),
        ),
        RefQuestion(
            "Расскажите, как используете ИИ в своей работе?",
            null,
            listOf("Конкретные инструменты", "Критическое отношение к выводу модели"),
        ),
    )

    /** Персональные вопросы (Р-12) — в скелете заглушка вместо LLM. */
    fun personalQuestions(resumeText: String): List<RefQuestion> {
        val hint = resumeText.take(60).replace("\n", " ").trim()
        return listOf(
            RefQuestion(
                "В резюме упомянут опыт, который хотелось бы уточнить: «$hint…». " +
                    "Расскажите, какой конкретно была ваша роль в этом проекте и за что вы отвечали лично?",
                null,
                listOf("Личный вклад, а не работа команды", "Конкретные решения кандидата"),
            ),
            RefQuestion(
                "Какой самый большой по нагрузке или объёму данных сервис вы вели, " +
                    "и какими цифрами можете это подтвердить?",
                null,
                listOf("Масштаб в числах", "Понимание узких мест"),
            ),
        )
    }

    private fun req(
        text: String,
        kind: RequirementKind,
        weight: Int,
        stopFactor: Boolean = false,
        notVerifiable: Boolean = false,
    ) = RequirementInput(
        id = null, text = text, kind = kind, weight = weight,
        stopFactor = stopFactor, notVerifiable = notVerifiable,
    )

    fun toQuestions(source: List<RefQuestion>, requirements: List<Requirement>, origin: QuestionOrigin): List<Question> =
        source.mapIndexed { index, q ->
            Question(
                id = UUID.randomUUID(),
                ord = index + 1,
                text = q.text,
                requirementId = requirements.find { it.text == q.requirementText }?.id,
                strongSignals = q.signals,
                origin = origin,
            )
        }
}
