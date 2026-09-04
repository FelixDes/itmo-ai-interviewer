package com.itmo.napoleonit.aiinterviewer.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Единый формат ошибок, docs/api.md §1. */
data class ApiErrorBody(
    val code: String,
    val message: String,
    val details: Map<String, String>? = null,
)

open class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: Map<String, String>? = null,
) : RuntimeException(message)

class NotFound(message: String = "Не найдено") :
    ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message)

class Forbidden(code: String = "FORBIDDEN", message: String = "Нет доступа") :
    ApiException(HttpStatus.FORBIDDEN, code, message)

class LinkExpired(message: String = "Срок действия ссылки истёк") :
    ApiException(HttpStatus.GONE, "LINK_EXPIRED", message)

class InvalidState(message: String) :
    ApiException(HttpStatus.CONFLICT, "INVALID_STATE", message)

class Conflict(code: String, message: String) :
    ApiException(HttpStatus.CONFLICT, code, message)

class ValidationFailed(details: Map<String, String>) :
    ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Невалидные данные", details)

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ApiException::class)
    fun handle(e: ApiException): ResponseEntity<ApiErrorBody> =
        ResponseEntity.status(e.status).body(ApiErrorBody(e.code, e.message, e.details))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ApiErrorBody> {
        val details = e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "невалидно") }
        return ResponseEntity.badRequest().body(ApiErrorBody("VALIDATION_FAILED", "Невалидные данные", details))
    }

    /**
     * Тело не разобралось: неизвестное значение enum, кривой JSON, не тот тип.
     * Это ошибка клиента, а не сервера, поэтому 400, а не 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException): ResponseEntity<ApiErrorBody> =
        ResponseEntity.badRequest().body(
            ApiErrorBody(
                "VALIDATION_FAILED",
                "Тело запроса не разобралось",
                mapOf("body" to (e.mostSpecificCause.message?.take(300) ?: "невалидный JSON")),
            )
        )

    @ExceptionHandler(Exception::class)
    fun handleOther(e: Exception): ResponseEntity<ApiErrorBody> =
        ResponseEntity.internalServerError()
            .body(ApiErrorBody("INTERNAL", e.message ?: e.javaClass.simpleName))
}
