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
        val publicEndpoint: String,
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
    )

    data class Asr(
        val baseUrl: String,
        val timeout: Duration,
        val stubDelayMs: Long = 2500,
    )

    data class Tts(
        val baseUrl: String,
        val timeout: Duration,
    )

    data class Interview(
        val candidateLinkTtlDays: Long,
        val shareLinkTtlDays: Long,
        val maxAnswerDurationSec: Int,
        val maxFollowupsPerAnswer: Int,
        val maxTotalQuestions: Int,
    )
}
