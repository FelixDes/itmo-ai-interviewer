package com.itmo.napoleonit.aiinterviewer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class AiinterviewerApplication

fun main(args: Array<String>) {
    runApplication<AiinterviewerApplication>(*args)
}
