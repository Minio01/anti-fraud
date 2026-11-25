package com.antifraud.mock.api

import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class MockExceptionHandler {
    @ExceptionHandler(ResponseStatusException::class)
    fun statusError(exception: ResponseStatusException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(exception.statusCode, exception.reason ?: "Ошибка запроса")
}
