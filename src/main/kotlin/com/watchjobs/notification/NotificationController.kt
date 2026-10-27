package com.watchjobs.notification

import com.watchjobs.security.AppUserDetails
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/notifications")
class NotificationController(
    private val service: NotificationService,
    private val tokens: UnsubscribeTokens
) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: AppUserDetails,
        @RequestParam(defaultValue = "false") unread: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): NotificationPage = service.list(principal.id, unread, page, size)

    @GetMapping("/unread-count")
    fun unreadCount(@AuthenticationPrincipal principal: AppUserDetails): UnreadCount = service.unreadCount(principal.id)

    @PostMapping("/{id}/read")
    fun read(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Long): ResponseEntity<Void> {
        service.markRead(principal.id, id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/read-all")
    fun readAll(@AuthenticationPrincipal principal: AppUserDetails): ResponseEntity<Void> {
        service.markAllRead(principal.id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/preferences")
    fun preference(@AuthenticationPrincipal principal: AppUserDetails): NotificationPreferenceResponse =
        service.preference(principal.id)

    @PutMapping("/preferences")
    fun updatePreference(
        @AuthenticationPrincipal principal: AppUserDetails,
        @Valid @RequestBody request: NotificationPreferenceRequest
    ): NotificationPreferenceResponse = service.updatePreference(principal.id, request)

    // Link do e-mail (público): desliga os e-mails sem pedir login.
    @GetMapping("/unsubscribe", produces = [MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8"])
    fun unsubscribe(@RequestParam token: String): ResponseEntity<String> {
        val userId = tokens.verify(token) ?: return ResponseEntity.badRequest().body("Link inválido.")
        service.disableEmail(userId)
        return ResponseEntity.ok("Pronto: você não vai mais receber e-mails de avisos do WatchJobs. Dá para religar no app.")
    }
}
