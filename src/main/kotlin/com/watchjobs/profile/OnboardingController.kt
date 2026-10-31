package com.watchjobs.profile

import com.watchjobs.security.AppUserDetails
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

data class OnboardingStatus(val completed: Boolean, val completedAt: OffsetDateTime?)

// Tour guiado (F06): o app pergunta se já foi visto e avisa quando o usuário conclui ou pula.
@RestController
@RequestMapping("/profile/onboarding")
class OnboardingController(private val jdbc: JdbcClient) {
    @GetMapping
    fun status(@AuthenticationPrincipal principal: AppUserDetails): OnboardingStatus = read(principal.id)

    @PostMapping("/complete")
    fun complete(@AuthenticationPrincipal principal: AppUserDetails): OnboardingStatus {
        jdbc.sql("UPDATE core.users SET onboarding_completed_at = COALESCE(onboarding_completed_at, now()) WHERE users_id = :userId")
            .param("userId", principal.id).update()
        return read(principal.id)
    }

    private fun read(userId: Int): OnboardingStatus {
        val at = jdbc.sql("SELECT onboarding_completed_at FROM core.users WHERE users_id = :userId")
            .param("userId", userId).query(OffsetDateTime::class.java).optional().orElse(null)
        return OnboardingStatus(at != null, at)
    }
}
