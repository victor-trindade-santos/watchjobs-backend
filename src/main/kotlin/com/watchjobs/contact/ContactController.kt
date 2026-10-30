package com.watchjobs.contact

import com.watchjobs.security.AppUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/saved-jobs/{savedJobId}/contacts")
class ContactController(private val service: ContactService) {

    @GetMapping
    fun list(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable savedJobId: Long): List<ContactResponse> =
        service.list(principal.id, savedJobId)

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable savedJobId: Long,
        @Valid @RequestBody request: ContactRequest
    ): ResponseEntity<ContactResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.id, savedJobId, request))

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable savedJobId: Long,
        @PathVariable id: Long,
        @Valid @RequestBody request: ContactRequest
    ): ContactResponse = service.update(principal.id, savedJobId, id, request)

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable savedJobId: Long,
        @PathVariable id: Long
    ): ResponseEntity<Void> {
        service.delete(principal.id, savedJobId, id)
        return ResponseEntity.noContent().build()
    }
}
