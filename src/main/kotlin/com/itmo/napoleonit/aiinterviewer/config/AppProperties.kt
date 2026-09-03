package com.itmo.napoleonit.aiinterviewer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val publicBaseUrl: String,
    val s3: S3,
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

    data class Interview(
        val candidateLinkTtlDays: Long,
        val shareLinkTtlDays: Long,
        val maxAnswerDurationSec: Int,
        val maxFollowupsPerAnswer: Int,
        val maxTotalQuestions: Int,
    )
}
