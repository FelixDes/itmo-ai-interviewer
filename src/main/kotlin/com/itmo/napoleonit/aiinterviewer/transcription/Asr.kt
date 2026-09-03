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
 * Боевому ASR текст вопроса не нужен и не должен быть нужен. Заглушке он нужен,
 * чтобы отвечать по теме, поэтому подсказка передаётся отдельным интерфейсом,
 * а не протаскивается в основной.
 */
interface QuestionAwareAsr {
    fun rememberQuestion(mediaKey: String, questionText: String)
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

    /**
     * Точные замены: слово целиком, окончание не трогаем.
     *
     * Сюда попадают ошибки, где стем трогать опасно («кавк» съел бы «Кавказ»),
     * и искажения русских слов. Список пополняется по тому, что реально выдаёт
     * faster-whisper на русской технической речи, а не по догадкам.
     */
    private val spellFixes = mapOf(
        "кавку" to "Kafka",
        "кавка" to "Kafka",
        "кавки" to "Kafka",
        "кавке" to "Kafka",
        "капку" to "Kafka",
        "дубликация" to "дедупликация",
        "дубликации" to "дедупликации",
        "идемпеотентность" to "идемпотентность",
        "идемпеотентности" to "идемпотентности",
        "потоковый апсерт" to "upsert",
    )

    // (?U) обязателен: без него \b считается по ASCII и перед кириллицей
    // границы слова просто нет, поэтому ни одна замена не срабатывает

    /** Стем плюс любое русское окончание: «кафку», «кафке» -> Kafka. */
    private val stemPatterns = latinTerms.entries
        .sortedByDescending { it.key.length }
        .map { (stem, term) -> Regex("(?U)\\b$stem[а-яё]*", RegexOption.IGNORE_CASE) to term }

    /**
     * Слово целиком. Границы обязательны с обеих сторон: без них «кавка»
     * находится внутри «Кавказский» и превращает его в «Kafkaзский».
     */
    private val exactPatterns = spellFixes.entries
        .sortedByDescending { it.key.length }
        .map { (word, term) -> Regex("(?U)\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE) to term }

    override fun refine(raw: String): String {
        var text = raw
        exactPatterns.forEach { (pattern, term) -> text = pattern.replace(text, term) }
        stemPatterns.forEach { (pattern, term) -> text = pattern.replace(text, term) }
        return text
    }
}
