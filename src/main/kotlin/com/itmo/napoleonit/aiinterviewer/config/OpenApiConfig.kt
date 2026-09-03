package com.itmo.napoleonit.aiinterviewer.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.tags.Tag
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Swagger UI на /swagger-ui.html.
 *
 * Группы повторяют разграничение доступа из docs/api.md: у каждой роли свой
 * префикс путей, и в интерфейсе это должно быть видно так же явно, как в коде.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("AI-интервьюер")
                .version("1.0")
                .description(
                    """
                    Асинхронное техническое видеоинтервью с ИИ-оценкой.

                    Три семейства путей, разграничение читается прямо в роутинге:
                    - `/api/...` — рекрутер, нужна сессия (POST /api/auth/login);
                    - `/api/s/{token}/...` — кандидат, доступ по токену из ссылки;
                    - `/api/r/{token}` — нанимающий менеджер, один отчёт только для чтения.

                    Полное описание контракта: docs/api.md.
                    Демо-данные: POST /api/demo/seed. Учётки: recruiter/recruiter, anna/anna.
                    """.trimIndent()
                )
        )
        .addTagsItem(Tag().name("Аутентификация").description("Вход рекрутера, сессия в куке"))
        .addTagsItem(Tag().name("Вакансии").description("Вакансии, требования, наборы вопросов"))
        .addTagsItem(Tag().name("Интервью").description("Создание, список, карточка, ссылки"))
        .addTagsItem(Tag().name("Кандидат").description("Прохождение интервью по токену"))
        .addTagsItem(Tag().name("Нанимающий менеджер").description("Просмотр одного отчёта"))
        .addTagsItem(Tag().name("Демо").description("Сид демо-данных"))

    @Bean
    fun recruiterApi(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("1-recruiter")
        .pathsToMatch("/api/auth/**", "/api/vacancies/**", "/api/question-sets/**", "/api/interviews/**", "/api/demo/**")
        .build()

    @Bean
    fun candidateApi(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("2-candidate")
        .pathsToMatch("/api/s/**")
        .build()

    @Bean
    fun managerApi(): GroupedOpenApi = GroupedOpenApi.builder()
        .group("3-manager")
        .pathsToMatch("/api/r/**")
        .build()
}
