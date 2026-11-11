package com.antifraud.mock

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TransactionMockApplication

fun main(args: Array<String>) {
    runApplication<TransactionMockApplication>(*args)
}
