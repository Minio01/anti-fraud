package com.antifraud.profile

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ProfileWorkerApplication

fun main(args: Array<String>) {
    runApplication<ProfileWorkerApplication>(*args)
}
