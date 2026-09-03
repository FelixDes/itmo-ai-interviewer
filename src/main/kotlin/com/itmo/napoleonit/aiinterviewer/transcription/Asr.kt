package com.itmo.napoleonit.aiinterviewer.transcription

import com.itmo.napoleonit.aiinterviewer.domain.TranscriptSegment
import org.springframework.stereotype.Component

/** Транскрибация за интерфейсом (Р-8). Здесь встанет NeMo, fallback — WhisperX. */
data class AsrResult(
    val text: String,
    val segments: List<TranscriptSegment>,
    val model: String,
    /** Записи бывают непригодные — тогда Рамка §7 требует честного «оценить нельзя». */
    val usable: Boolean = true,
)

interface AsrEngine {
    fun transcribe(mediaKey: String, contentType: String?): AsrResult
}

/**
 * Выравнивание техжаргона вторым проходом.
 *
 * Словарь набран по реальным искажениям из эталонного транскрипта заказчика:
 * там Whisper выдал «Fastream», «отсёрты», «идемпеотентность», «воркмемор».
 * Боевая версия сделает то же самое через LLM, интерфейс не изменится.
 */
interface TranscriptRefiner {
    fun refine(raw: String): String
}

@Component
class DictionaryTranscriptRefiner : TranscriptRefiner {

    /**
     * Латинские термины: ASR пишет их кириллицей и склоняет. Регулярка съедает
     * русское окончание целиком, иначе получается «GitLabа» и «DevOpsами».
     */
    private val latinTerms = mapOf(
        "кафк" to "Kafka",
        "постгрес" to "PostgreSQL",
        "докер" to "Docker",
        "кубернетес" to "Kubernetes",
        "кубер" to "Kubernetes",
        "редис" to "Redis",
        "гуид" to "GUID",
        "аутбокс" to "Outbox",
        "эксплейн" to "EXPLAIN",
        "фастапи" to "FastAPI",
        "фастрим" to "FastStream",
        "джанго" to "Django",
        "селери" to "Celery",
        "девопс" to "DevOps",
        "гитлаб" to "GitLab",
        "кликхаус" to "ClickHouse",
        "графан" to "Grafana",
        "прометей" to "Prometheus",
        "воркмемор" to "work_mem",
        "апсёрт" to "upsert",
        "апсерт" to "upsert",
        "отсёрт" to "upsert",
    )

    /** Ошибки распознавания русских слов: здесь окончание трогать нельзя. */
    private val spellFixes = mapOf(
        "идемпеотентность" to "идемпотентность",
        "идемпеотентности" to "идемпотентности",
        "корутин" to "корутин",
    )

    private val patterns = latinTerms.entries
        .sortedByDescending { it.key.length }
        // (?U) обязателен: без него \b считается по ASCII и перед кириллицей
        // границы слова просто нет, поэтому ни одна замена не срабатывает
        .map { (stem, term) -> Regex("(?U)\\b$stem[а-яё]*", RegexOption.IGNORE_CASE) to term }

    override fun refine(raw: String): String {
        var text = spellFixes.entries.fold(raw) { acc, (from, to) ->
            acc.replace(from, to, ignoreCase = true)
        }
        patterns.forEach { (pattern, term) -> text = pattern.replace(text, term) }
        return text
    }
}
