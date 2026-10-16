package com.watchjobs.controller

import com.watchjobs.dto.request.SavedJobRequest
import com.watchjobs.dto.response.SavedJobResponse
import com.watchjobs.security.AppUserDetails
import com.watchjobs.service.SavedJobService
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
@RequestMapping("/saved-jobs")
class SavedJobController(
    private val savedJobService: SavedJobService
) {
    @GetMapping
    fun findAll(
        @AuthenticationPrincipal principal: AppUserDetails,
        @RequestParam(required = false) q: String?
    ): List<SavedJobResponse> = savedJobService.findAll(principal.id, q)

    @GetMapping("/{id}")
    fun findById(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Long
    ): SavedJobResponse = savedJobService.findById(principal.id, id)

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: AppUserDetails,
        @Valid @RequestBody request: SavedJobRequest
    ): ResponseEntity<SavedJobResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(savedJobService.create(principal.id, request))

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Long,
        @Valid @RequestBody request: SavedJobRequest
    ): SavedJobResponse = savedJobService.update(principal.id, id, request)

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Long
    ): ResponseEntity<Void> {
        savedJobService.delete(principal.id, id)
        return ResponseEntity.noContent().build()
    }
}
