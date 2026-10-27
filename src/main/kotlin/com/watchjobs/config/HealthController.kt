package com.watchjobs.config

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

// Vivo? Não toca no banco, para não acordar o Neon à toa (Cloud Run e teste pós-deploy).
@RestController
class HealthController {
    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf("status" to "UP")
}
