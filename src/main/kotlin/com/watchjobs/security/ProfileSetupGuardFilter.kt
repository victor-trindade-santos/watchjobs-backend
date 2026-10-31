package com.watchjobs.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

// F05: enquanto o assistente de perfil não for concluído, só o perfil (e o que ele usa) fica liberado.
// Ligado por app.profile-setup.enforce (padrão: ligado em produção, desligado no desenvolvimento e nos testes).
class ProfileSetupGuardFilter(private val enforce: Boolean) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (!enforce) return true
        val path = request.requestURI.removePrefix(request.contextPath)
        return request.method == "OPTIONS" || ALLOWED_PREFIXES.any { path == it || path.startsWith("$it/") }
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AppUserDetails
        if (principal != null && principal.user.profileSetupCompletedAt == null) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.characterEncoding = "UTF-8"
            response.contentType = "application/json"
            response.writer.write(
                """{"code":"PROFILE_SETUP_REQUIRED","message":"Conclua a configuração do perfil para acessar esta área.","details":null}"""
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    private companion object {
        val ALLOWED_PREFIXES = listOf(
            "/me", "/auth", "/signin", "/signup", "/password", "/health",
            "/profile", "/catalog", "/notifications/unread-count", "/notifications/devices", "/notifications/unsubscribe",
            "/swagger-ui", "/swagger-ui.html", "/v3/api-docs", "/webjars", "/error"
        )
    }
}
