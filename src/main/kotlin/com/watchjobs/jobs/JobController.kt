package com.watchjobs.jobs

import com.watchjobs.dto.response.SavedJobResponse
import com.watchjobs.entity.enums.ExperienceLevel
import com.watchjobs.entity.enums.JobType
import com.watchjobs.entity.enums.WorkMode
import com.watchjobs.security.AppUserDetails
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// Busca no corpus (não consulta fonte externa), detalhe e "salvar do corpus".
@RestController
@RequestMapping("/jobs")
class JobController(private val service: JobCorpusService) {

    @GetMapping("/search")
    fun search(
        @AuthenticationPrincipal principal: AppUserDetails,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) location: String?,
        @RequestParam(required = false) workMode: WorkMode?,
        @RequestParam(required = false) level: ExperienceLevel?,
        @RequestParam(required = false) jobType: JobType?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): JobSearchResponse =
        service.search(principal.id, JobSearchFilter(q, location, workMode, level, jobType, page, size))

    @GetMapping("/{id}")
    fun detail(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Long
    ): JobDetailResponse = service.detail(principal.id, id)

    @PostMapping("/{id}/save")
    fun save(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Long
    ): ResponseEntity<SavedJobResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.save(principal.id, id))
}
