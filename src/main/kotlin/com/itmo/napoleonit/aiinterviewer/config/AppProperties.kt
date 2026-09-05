package com.itmo.napoleonit.aiinterviewer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val publicBaseUrl: String,
    val s3: S3,
    val llm: Llm,
    val asr: Asr,
    val tts: Tts,
    val interview: Interview,
) {
    data class S3(
        val endpoint: String,
        /** Адрес, по которому за медиа ходит браузер. */
        val publicEndpoint: String,
        /**
         * Адрес для сервисов внутри compose-сети. Presigned-ссылка привязана
         * к хосту, поэтому подписанная для браузера в контейнере не сработает.
         */
        val internalEndpoint: String,
        val region: String,
        val bucket: String,
        val accessKey: String,
        val secretKey: String,
        val uploadUrlTtl: Duration,
        val downloadUrlTtl: Duration,
    )

    /** OpenAI-совместимый endpoint: LM Studio локально, но меняется одним конфигом (Р-5). */
    data class Llm(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val timeout: Duration,
        val temperature: Double = 0.2,
        val structuredOutput: StructuredOutput = StructuredOutput.AUTO,
        /**
         * Глубина рассуждений там, где ждёт кандидат: между вопросами интервью.
         * У рассуждающих моделей это главный источник задержки.
         */
        val reasoningEffort: String = "low",
        /** Там, где ждёт рекрутер и вызов разовый, качество важнее секунд. */
        val reasoningEffortDeep: String = "high",
    )

    /** Насколько строго провайдер умеет держать формат ответа. */
    enum class StructuredOutput { AUTO, JSON_SCHEMA, JSON_OBJECT, NONE }

    data class Asr(
        val baseUrl: String,
        val timeout: Duration,
        val stubDelayMs: Long = 2500,
    )

    data class Tts(
        val baseUrl: String,
        val timeout: Duration,
        val defaultVoice: String = "xenia",
        /**
         * Голоса, которые предлагаем кандидату. Список наш, а не модели:
         * подписи нужны человеческие, а состав — управляемый.
         */
        val voices: List<Voice> = emptyList(),
    )

    data class Voice(val id: String, val name: String)

    data class Interview(
        val candidateLinkTtlDays: Long,
        val shareLinkTtlDays: Long,
        val maxAnswerDurationSec: Int,
        val maxFollowupsPerAnswer: Int,
        val maxTotalQuestions: Int,
    )
}
