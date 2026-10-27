package com.watchjobs.config

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

// Perfil migrate: o Flyway já rodou na subida do contexto (com o usuário dono); aqui só termina o processo.
@Component
@Profile("migrate")
class MigrateRunner(private val context: ApplicationContext) : CommandLineRunner {
    override fun run(vararg args: String) {
        LoggerFactory.getLogger(javaClass).info("Migrations aplicadas")
        exitProcess(SpringApplication.exit(context, ExitCodeGenerator { 0 }))
    }
}
