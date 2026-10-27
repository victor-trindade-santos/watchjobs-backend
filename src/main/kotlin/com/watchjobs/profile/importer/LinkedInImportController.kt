package com.watchjobs.profile.importer

import com.watchjobs.security.AppUserDetails
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/profile/import")
class LinkedInImportController(private val service: LinkedInImportService) {

    @PostMapping("/linkedin", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @AuthenticationPrincipal principal: AppUserDetails,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<ImportResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.preview(principal.id, file.bytes))

    @PostMapping("/resume", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadResume(
        @AuthenticationPrincipal principal: AppUserDetails,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<ImportResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.previewResume(principal.id, file.bytes))

    @GetMapping("/{id}")
    fun find(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Long) = service.find(principal.id, id)

    @PostMapping("/{id}/apply")
    fun apply(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Long,
        @RequestBody(required = false) request: ApplyImportRequest?
    ) = service.apply(principal.id, id, request ?: ApplyImportRequest())

    @DeleteMapping("/{id}")
    fun discard(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Long): ResponseEntity<Void> {
        service.discard(principal.id, id)
        return ResponseEntity.noContent().build()
    }
}
