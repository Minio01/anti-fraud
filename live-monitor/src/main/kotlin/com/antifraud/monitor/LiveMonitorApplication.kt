package com.antifraud.monitor

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LiveMonitorApplication

fun main(args: Array<String>) {
    runApplication<LiveMonitorApplication>(*args)
}
