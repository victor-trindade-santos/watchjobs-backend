package com.watchjobs.ingestion.pipeline

import com.watchjobs.ingestion.normalize.NormalizedJob

// Política de local da fonte (config locationPolicy). BR_OR_REMOTE, o padrão, guarda as vagas no Brasil e as
// remotas de qualquer país; ANY guarda tudo.
enum class LocationPolicy {
    BR_OR_REMOTE,
    ANY;

    // País desconhecido passa: só sai a vaga presencial ou híbrida que sabidamente fica fora do Brasil.
    fun accepts(job: NormalizedJob): Boolean =
        this == ANY || job.workMode == "REMOTE" || job.countryCode == null || job.countryCode == "BR"

    companion object {
        fun of(config: Map<String, Any?>): LocationPolicy =
            (config["locationPolicy"] as? String)?.trim()?.let { value -> entries.firstOrNull { it.name.equals(value, ignoreCase = true) } }
                ?: BR_OR_REMOTE
    }
}
