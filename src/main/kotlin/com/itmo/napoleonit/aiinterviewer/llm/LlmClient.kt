package com.itmo.napoleonit.aiinterviewer.llm

import com.itmo.napoleonit.aiinterviewer.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder
import org.springframework.boot.http.client.HttpClientSettings
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Клиент к OpenAI-совместимому /v1/chat/completions (Р-5).
 *
 * Никаких вендор-специфичных SDK: провайдер задаётся тремя настройками,
 * переезд с LM Studio на что угодно другое — правка конфига.
 */
@Component
class LlmClient(
    private val props: AppProperties,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val warnedUnavailable = AtomicBoolean(false)

    @Volatile
    private var resolvedModel: String? = null

    @Volatile
    private var effectiveMode: AppProperties.StructuredOutput? = null

    private val client: RestClient = RestClient.builder()
        .baseUrl(props.llm.baseUrl)
        .defaultHeader("Authorization", "Bearer ${props.llm.apiKey}")
        .requestFactory(
            ClientHttpRequestFactoryBuilder.detect().build(
                HttpClientSettings.defaults()
                    .withTimeouts(Duration.ofSeconds(5), props.llm.timeout)
            )
        )
        .build()

    /**
     * Запрос со структурированным ответом.
     *
     * Провайдеры держат формат по-разному: OpenAI и LM Studio умеют строгую
     * json_schema, DeepSeek — только общий json_object. В режиме auto пробуем
     * строгую схему и при отказе провайдера один раз понижаемся, запомнив выбор.
     *
     * Возвращает null, если модель недоступна или ответ не разобрался —
     * вызывающий обязан иметь запасной путь.
     */
    fun <T> completeJson(
        systemPrompt: String,
        userPrompt: String,
        schemaName: String,
        schema: Map<String, Any>,
        type: Class<T>,
        reasoningEffort: String = props.llm.reasoningEffort,
    ): T? {
        val content = request(systemPrompt, userPrompt, schemaName, schema, mode(), reasoningEffort)
            ?: return null
        return runCatching { mapper.readValue(extractJson(content), type) }
            .onFailure { log.warn("Ответ модели не разобрался как {}: {}", schemaName, content.take(400)) }
            .getOrNull()
    }

    private fun request(
        systemPrompt: String,
        userPrompt: String,
        schemaName: String,
        schema: Map<String, Any>,
        mode: AppProperties.StructuredOutput,
        reasoningEffort: String,
    ): String? {
        // В нестрогих режимах схему кладём в промпт: иначе модели не на что опереться
        val system = if (mode == AppProperties.StructuredOutput.JSON_SCHEMA) systemPrompt else
            systemPrompt + "\n\nВерни ответ строго как JSON по схеме, без пояснений вокруг:\n" +
                mapper.writeValueAsString(schema)

        val body = buildMap<String, Any> {
            put("model", modelId())
            put("temperature", props.llm.temperature)
            if (reasoningEffort.isNotBlank()) put("reasoning_effort", reasoningEffort)
            put(
                "messages",
                listOf(
                    mapOf("role" to "system", "content" to system),
                    mapOf("role" to "user", "content" to userPrompt),
                )
            )
            when (mode) {
                AppProperties.StructuredOutput.JSON_SCHEMA -> put(
                    "response_format",
                    mapOf(
                        "type" to "json_schema",
                        "json_schema" to mapOf("name" to schemaName, "strict" to true, "schema" to schema),
                    )
                )
                AppProperties.StructuredOutput.JSON_OBJECT ->
                    put("response_format", mapOf("type" to "json_object"))
                else -> Unit
            }
        }

        val result = call(body)
        if (result.content != null) return result.content

        // Провайдер не понял строгую схему — понижаемся и повторяем один раз
        if (mode == AppProperties.StructuredOutput.JSON_SCHEMA && result.rejectedFormat) {
            log.warn("Провайдер не принял json_schema, переходим на json_object")
            effectiveMode = AppProperties.StructuredOutput.JSON_OBJECT
            return request(
                systemPrompt, userPrompt, schemaName, schema,
                AppProperties.StructuredOutput.JSON_OBJECT, reasoningEffort,
            )
        }
        return null
    }

    private fun mode(): AppProperties.StructuredOutput {
        effectiveMode?.let { return it }
        val configured = props.llm.structuredOutput
        val resolved = if (configured == AppProperties.StructuredOutput.AUTO) {
            AppProperties.StructuredOutput.JSON_SCHEMA
        } else configured
        effectiveMode = resolved
        return resolved
    }

    fun available(): Boolean = models().isNotEmpty()

    /**
     * id модели. Если в конфиге стоит auto или пусто — берём первую загруженную:
     * в LM Studio id меняется от модели к модели, и промахнуться им слишком легко.
     */
    fun modelId(): String {
        val configured = props.llm.model
        if (configured.isNotBlank() && configured != "auto") return configured
        resolvedModel?.let { return it }
        val first = models().firstOrNull() ?: return "auto"
        resolvedModel = first
        log.info("Модель определена автоматически: {}", first)
        return first
    }

    fun models(): List<String> = runCatching {
        client.get().uri("/models").retrieve().body(ModelsResponse::class.java)
            ?.data.orEmpty().mapNotNull { it.id }
    }.getOrDefault(emptyList())

    private data class CallResult(val content: String?, val rejectedFormat: Boolean = false)

    private fun call(body: Map<String, Any>): CallResult {
        return try {
            val response = client.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(ChatResponse::class.java)
            warnedUnavailable.set(false)
            CallResult(response?.choices?.firstOrNull()?.message?.content)
        } catch (e: HttpClientErrorException) {
            val text = e.responseBodyAsString
            val rejected = text.contains("response_format", true) ||
                text.contains("json_schema", true) ||
                e.statusCode.value() == 422
            if (!rejected) log.warn("LLM ответила {}: {}", e.statusCode, text.take(300))
            CallResult(null, rejected)
        } catch (e: Exception) {
            // Шумим один раз: провайдер может быть недоступен всю сессию
            if (warnedUnavailable.compareAndSet(false, true)) {
                log.warn("LLM недоступна ({}), работаем на правилах: {}", props.llm.baseUrl, e.message)
            }
            CallResult(null)
        }
    }

    /** Некоторые модели заворачивают JSON в ```-блок даже при strict-схеме. */
    private fun extractJson(content: String): String {
        val trimmed = content.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    data class ModelsResponse(val data: List<Model> = emptyList()) {
        data class Model(val id: String? = null)
    }

    data class ChatResponse(val choices: List<Choice> = emptyList()) {
        data class Choice(val message: Message? = null)
        data class Message(val content: String? = null)
    }
}

/** Мини-помощники сборки JSON Schema, чтобы промпты читались, а не тонули в мапах. */
object Schema {
    fun obj(vararg properties: Pair<String, Map<String, Any>>): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to properties.toMap(),
        "required" to properties.map { it.first },
        "additionalProperties" to false,
    )

    fun string(description: String? = null): Map<String, Any> = buildMap {
        put("type", "string")
        description?.let { put("description", it) }
    }

    fun enumOf(vararg values: String): Map<String, Any> = mapOf("type" to "string", "enum" to values.toList())

    fun integer(min: Int, max: Int): Map<String, Any> =
        mapOf("type" to "integer", "minimum" to min, "maximum" to max)

    fun bool(): Map<String, Any> = mapOf("type" to "boolean")

    fun array(items: Map<String, Any>): Map<String, Any> = mapOf("type" to "array", "items" to items)
}
