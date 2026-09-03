package com.itmo.napoleonit.aiinterviewer.llm

import com.itmo.napoleonit.aiinterviewer.config.AppProperties
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Видно в /actuator/health: поднята ли модель.
 *
 * Статус всегда UP — отсутствие LLM не поломка, система штатно работает на
 * правилах. Правда лежит в деталях, чтобы можно было увидеть, что демо идёт
 * без модели, и не гадать.
 */
@Component("llm")
class LlmHealthIndicator(
    private val llm: LlmClient,
    private val props: AppProperties,
) : HealthIndicator {

    override fun health(): Health {
        val models = llm.models()
        return Health.up()
            .withDetail("baseUrl", props.llm.baseUrl)
            .withDetail("available", models.isNotEmpty())
            .withDetail("model", if (models.isEmpty()) "нет связи, работаем на правилах" else llm.modelId())
            .withDetail("loadedModels", models)
            .build()
    }
}
