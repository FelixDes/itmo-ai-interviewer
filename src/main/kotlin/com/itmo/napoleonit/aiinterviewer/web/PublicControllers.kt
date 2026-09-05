package com.itmo.napoleonit.aiinterviewer.web

import com.itmo.napoleonit.aiinterviewer.service.CandidateService
import com.itmo.napoleonit.aiinterviewer.service.InterviewService
import com.itmo.napoleonit.aiinterviewer.web.dto.*
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Сессия кандидата. Доступ по токену в URL, без куки и логина. */
@RestController
@RequestMapping("/api/s/{token}")
class CandidateController(private val service: CandidateService) {

    @GetMapping
    fun state(@PathVariable token: String): CandidateState = service.state(token)

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

    @PostMapping("/voice")
    fun chooseVoice(@PathVariable token: String, @RequestBody body: ChooseVoiceRequest): CandidateState =
        service.chooseVoice(token, body.voice)

    /** Короткий пример голоса, чтобы выбрать на слух, а не по названию. */
    @GetMapping("/voices/{voice}/sample")
    fun voiceSample(@PathVariable token: String, @PathVariable voice: String): ResponseEntity<ByteArray> {
        val (bytes, contentType) = service.voiceSample(token, voice)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
            .body(bytes)
    }

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
        val (bytes, contentType) = service.audio(token, questionId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
            .body(bytes)
    }
}

/** Нанимающий менеджер: один отчёт по токену, ничего больше. */
@RestController
@RequestMapping("/api/r")
class ManagerController(private val service: InterviewService) {

    @GetMapping("/{token}")
    fun report(@PathVariable token: String): Report = service.reportByShareToken(token)
}
