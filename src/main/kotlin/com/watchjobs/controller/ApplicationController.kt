package com.watchjobs.controller

import com.watchjobs.dto.request.ApplicationRequest
import com.watchjobs.dto.response.ApplicationResponse
import com.watchjobs.security.AppUserDetails
import com.watchjobs.service.ApplicationService
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/applications")
class ApplicationController(
    private val applicationService: ApplicationService
) {
    @GetMapping
    fun findAll(
        @AuthenticationPrincipal principal: AppUserDetails,
        @RequestParam(required = false) q: String?
    ): List<ApplicationResponse> = applicationService.findAll(principal.id, q)

    @GetMapping("/{id}")
    fun findById(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Int
    ): ApplicationResponse = applicationService.findById(principal.id, id)

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: AppUserDetails,
        @Valid @RequestBody request: ApplicationRequest
    ): ResponseEntity<ApplicationResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(applicationService.create(principal.id, request))

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Int,
        @Valid @RequestBody request: ApplicationRequest
    ): ApplicationResponse = applicationService.update(principal.id, id, request)

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Int
    ): ResponseEntity<Void> {
        applicationService.delete(principal.id, id)
        return ResponseEntity.noContent().build()
    }
}
