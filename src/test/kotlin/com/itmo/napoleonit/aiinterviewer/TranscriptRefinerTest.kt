package com.itmo.napoleonit.aiinterviewer

import com.itmo.napoleonit.aiinterviewer.transcription.DictionaryTranscriptRefiner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Выравнивание техжаргона — единственное место, где заглушка делает настоящую
 * работу, и где легко всё сломать регуляркой. Примеры взяты из реальных
 * искажений в эталонном транскрипте заказчика.
 */
class TranscriptRefinerTest {

    private val refiner = DictionaryTranscriptRefiner()

    @Test
    fun `латинские термины подставляются вместе с русским окончанием`() {
        assertEquals("Kafka", refiner.refine("кафку"))
        assertEquals("Kafka", refiner.refine("кафке"))
        assertEquals("GitLab", refiner.refine("гитлаба"))
        assertEquals("DevOps", refiner.refine("девопсами"))
        assertEquals("PostgreSQL", refiner.refine("постгресе"))
    }

    @Test
    fun `замена работает внутри предложения и не липнет к соседям`() {
        assertEquals(
            "Использовали Kafka на продакшене, дублирование настроено DevOps.",
            refiner.refine("Использовали кафку на продакшене, дублирование настроено девопсами."),
        )
    }

    @Test
    fun `русские слова чинятся без потери окончания`() {
        assertEquals("идемпотентность", refiner.refine("идемпеотентность"))
    }

    @Test
    fun `чинятся реальные ошибки faster-whisper на русской техречи`() {
        // Взято с живого прогона: Silero произнёс фразу, faster-whisper распознал так
        assertEquals(
            "Мы использовали Kafka на продакшене, где дедупликация шла по идентификатору сообщения.",
            refiner.refine(
                "Мы использовали кавку на продакшене, где дубликация шла по идентификатору сообщения."
            ),
        )
    }

    @Test
    fun `похожие слова не ломаются заменой`() {
        // «кавк» как стем съел бы Кавказ, поэтому такие случаи — точной заменой
        assertEquals("Кавказский хребет", refiner.refine("Кавказский хребет"))
    }

    @Test
    fun `текст без жаргона не меняется`() {
        val text = "Сейчас нахожусь в поиске работы, хочется развиваться."
        assertEquals(text, refiner.refine(text))
    }
}
