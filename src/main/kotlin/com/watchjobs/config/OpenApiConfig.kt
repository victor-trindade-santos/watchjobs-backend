package com.watchjobs.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun openAPI(): OpenAPI {
        val schemeName = "bearer-key"
        return OpenAPI()
            .info(
                Info()
                    .title("WatchJobs")
                    .version("0.0.1")
                    .description("API do WatchJobs. Rotas públicas não exigem token. Nas demais, use Authorize com o access token do login.")
            )
            .components(
                Components().addSecuritySchemes(
                    schemeName,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token retornado por /signin ou /auth/refresh")
                )
            )
            .addSecurityItem(SecurityRequirement().addList(schemeName))
    }
}
