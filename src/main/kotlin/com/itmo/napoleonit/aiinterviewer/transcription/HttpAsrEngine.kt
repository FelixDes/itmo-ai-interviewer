package com.itmo.napoleonit.aiinterviewer.transcription

import com.itmo.napoleonit.aiinterviewer.config.AppProperties
import com.itmo.napoleonit.aiinterviewer.domain.TranscriptSegment
import com.itmo.napoleonit.aiinterviewer.media.S3Service
import org.slf4j.LoggerFactory
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Распознавание через сервис asr (faster-whisper).
 *
 * Медиа сервис забирает из MinIO сам, по presigned-ссылке: гонять гигабайты
 * видео через JVM незачем. Ссылка подписывается на внутренний адрес сети,
 * потому что подпись привязана к хосту.
 */
@Primary
@Component
class HttpAsrEngine(
    private val props: AppProperties,
    private val s3: S3Service,
    private val fallback: StubAsrEngine,
) : AsrEngine, QuestionAwareAsr {

    private val log = LoggerFactory.getLogger(javaClass)
    private val warned = AtomicBoolean(false)

    private val client: RestClient = RestClient.builder()
        .baseUrl(props.asr.baseUrl)
        .requestFactory(
            // simple, а не detect: JDK-клиент пробует апгрейд до h2c,
            // а uvicorn его не понимает и теряет тело запроса
            ClientHttpRequestFactoryBuilder.simple().build(
                HttpClientSettings.defaults().withTimeouts(Duration.ofSeconds(5), props.asr.timeout)
            )
        )
        .build()

    /** Пробрасываем в заглушку: пригодится, если сервис окажется недоступен. */
    override fun rememberQuestion(mediaKey: String, questionText: String) =
        fallback.rememberQuestion(mediaKey, questionText)

    override fun transcribe(mediaKey: String, contentType: String?): AsrResult {
        val response = runCatching {
            client.post()
                .uri("/transcribe")
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("media_url" to s3.presignDownloadForServices(mediaKey)))
                .retrieve()
                .body(TranscribeResponse::class.java)
        }.onFailure { e ->
            if (warned.compareAndSet(false, true)) {
                log.warn("Сервис ASR недоступен ({}), работаем на заглушке: {}", props.asr.baseUrl, e.message)
            }
        }.getOrNull() ?: return fallback.transcribe(mediaKey, contentType)

        warned.set(false)

        val segments = response.segments.map {
            TranscriptSegment(startMs = it.start_ms, endMs = it.end_ms, text = it.text)
        }
        return AsrResult(
            text = response.text,
            segments = segments,
            model = response.model,
            usable = response.usable,
        )
    }

    data class TranscribeResponse(
        val text: String = "",
        val segments: List<Segment> = emptyList(),
        val model: String = "unknown",
        val usable: Boolean = true,
    ) {
        data class Segment(val start_ms: Long = 0, val end_ms: Long = 0, val text: String = "")
    }
}
