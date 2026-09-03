package com.itmo.napoleonit.aiinterviewer.web

import com.itmo.napoleonit.aiinterviewer.stub.StubService
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Сессия кандидата. Доступ по токену в URL, без куки и логина. */
@RestController
@RequestMapping("/api/s/{token}")
class CandidateController(private val service: StubService) {

    @GetMapping
    fun state(@PathVariable token: String): CandidateState = service.candidateState(token)

    @PostMapping("/consent")
    fun consent(@PathVariable token: String): CandidateState = service.consent(token)

    @PostMapping("/start")
    fun start(@PathVariable token: String): CandidateState = service.start(token)

    @PostMapping("/answers")
    fun startAnswer(@PathVariable token: String, @RequestBody body: StartAnswerRequest): AnswerUpload =
        service.startAnswer(token, body)

    @PostMapping("/answers/{answerId}/complete")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun complete(
        @PathVariable token: String,
        @PathVariable answerId: UUID,
        @RequestBody(required = false) body: CompleteAnswerRequest?,
    ): CandidateState = service.completeAnswer(token, answerId, body?.durationMs)

    @PostMapping("/answers/{answerId}/retry-upload")
    fun retryUpload(@PathVariable token: String, @PathVariable answerId: UUID): AnswerUpload =
        service.retryUpload(token, answerId)

    /** Пропуск вопроса: ответа ещё нет, поэтому адресуется вопросом, а не ответом. */
    @PostMapping("/questions/{questionId}/skip")
    fun skip(@PathVariable token: String, @PathVariable questionId: UUID): CandidateState =
        service.skipQuestion(token, questionId)

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun event(@PathVariable token: String, @RequestBody body: AntifraudEventRequest) =
        service.recordEvent(token, body)

    /**
     * Озвучка вопроса. В скелете отдаёт тишину нужной длины, чтобы фронт мог
     * строить плеер против рабочего эндпоинта. Здесь встанет Silero (Р-22).
     */
    @GetMapping("/questions/{questionId}/audio")
    fun audio(@PathVariable token: String, @PathVariable questionId: UUID): ResponseEntity<ByteArray> {
        val text = service.questionText(token, questionId)
        val seconds = (text.length / 15).coerceIn(2, 30)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("audio/wav"))
            .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
            .body(silentWav(seconds))
    }

    private fun silentWav(seconds: Int): ByteArray {
        val sampleRate = 8000
        val dataSize = sampleRate * seconds * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)                       // PCM
        header.putShort(1)                       // моно
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)            // байт в секунду
        header.putShort(2)                       // выравнивание блока
        header.putShort(16)                      // бит на сэмпл
        header.put("data".toByteArray())
        header.putInt(dataSize)
        out.write(header.array())
        out.write(ByteArray(dataSize))
        return out.toByteArray()
    }
}

/** Нанимающий менеджер: один отчёт по токену, ничего больше. */
@RestController
@RequestMapping("/api/r")
class ManagerController(private val service: StubService) {

    @GetMapping("/{token}")
    fun report(@PathVariable token: String): Report = service.reportByShareToken(token)
}
