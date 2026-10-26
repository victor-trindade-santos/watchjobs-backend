package com.watchjobs.ingestion

import com.watchjobs.ingestion.pipeline.IngestionPipeline
import com.watchjobs.ingestion.pipeline.RoundOptions
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

// O mesmo jar rodando como programa: coleta uma rodada e termina (perfil ingestion, sem servidor web).
// Código de saída 1 só quando todas as fontes tentadas falharam (o GitHub Actions avisa por e-mail).
@Component
@Profile("ingestion")
class IngestionRunner(
    private val pipeline: IngestionPipeline,
    private val context: ApplicationContext
) : CommandLineRunner {
    override fun run(vararg args: String) {
        val summary = pipeline.runRound(RoundOptions.parse(args))
        val code = if (summary.allFailed) 1 else 0
        exitProcess(SpringApplication.exit(context, ExitCodeGenerator { code }))
    }
}
