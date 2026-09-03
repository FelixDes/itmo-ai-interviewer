package com.itmo.napoleonit.aiinterviewer.persistence

import com.itmo.napoleonit.aiinterviewer.domain.TranscriptSegment
import com.itmo.napoleonit.aiinterviewer.web.dto.Evidence
import com.itmo.napoleonit.aiinterviewer.web.dto.Report
import com.itmo.napoleonit.aiinterviewer.web.dto.Scores
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Сериализация jsonb-колонок (Р-19).
 * Методы конкретные, без дженериков: типов немного, а reified здесь недоступен —
 * kotlin-spring делает бины открытыми.
 */
@Component
class Json(private val mapper: ObjectMapper) {

    fun write(value: Any?): String = if (value == null) "null" else mapper.writeValueAsString(value)

    fun strings(raw: String?): List<String> = readList(raw, String::class.java)

    fun segments(raw: String?): List<TranscriptSegment> = readList(raw, TranscriptSegment::class.java)

    fun quotes(raw: String?): List<Evidence> = readList(raw, Evidence::class.java)

    fun scores(raw: String?): Scores? = readOne(raw, Scores::class.java)

    fun report(raw: String?): Report? = readOne(raw, Report::class.java)

    private fun <T> readList(raw: String?, element: Class<T>): List<T> {
        if (empty(raw)) return emptyList()
        val type = mapper.typeFactory.constructCollectionType(List::class.java, element)
        return mapper.readValue(raw, type)
    }

    private fun <T> readOne(raw: String?, type: Class<T>): T? =
        if (empty(raw)) null else mapper.readValue(raw, type)

    private fun empty(raw: String?) = raw.isNullOrBlank() || raw == "null" || raw == "{}" || raw == "[]"
}
