package com.itmo.napoleonit.aiinterviewer.tts

import com.itmo.napoleonit.aiinterviewer.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** Озвучка через сервис tts (Silero). Недоступен — отдаём тишину, интервью не встаёт. */
@Primary
@Component
class HttpTtsEngine(
    private val props: AppProperties,
    private val fallback: SilenceTtsEngine,
) : TtsEngine {

    private val log = LoggerFactory.getLogger(javaClass)
    private val warned = AtomicBoolean(false)

    override val contentType = "audio/wav"
    override val model = "silero-v4-ru"

    private val client: RestClient = RestClient.builder()
        .baseUrl(props.tts.baseUrl)
        .requestFactory(
            // simple, а не detect: JDK-клиент пробует апгрейд до h2c,
            // а uvicorn его не понимает и теряет тело запроса
            ClientHttpRequestFactoryBuilder.simple().build(
                HttpClientSettings.defaults().withTimeouts(Duration.ofSeconds(5), props.tts.timeout)
            )
        )
        .build()

    override fun synthesize(text: String, voice: String?): ByteArray {
        val audio = runCatching {
            client.post()
                .uri("/synthesize")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.ALL)
                .body(buildMap {
                    put("text", text)
                    voice?.let { put("speaker", it) }
                })
                .retrieve()
                .body(ByteArray::class.java)
        }.onFailure { e ->
            if (warned.compareAndSet(false, true)) {
                log.warn("Сервис TTS недоступен ({}), отдаём тишину: {}", props.tts.baseUrl, e.message)
            }
        }.getOrNull()

        if (audio == null || audio.isEmpty()) return fallback.synthesize(text, voice)
        warned.set(false)
        return audio
    }
}
