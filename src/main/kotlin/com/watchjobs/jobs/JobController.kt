package com.watchjobs.jobs

import com.watchjobs.dto.response.SavedJobResponse
import com.watchjobs.entity.enums.ExperienceLevel
import com.watchjobs.entity.enums.JobType
import com.watchjobs.entity.enums.WorkMode
import com.watchjobs.exception.BusinessException
import com.watchjobs.matching.FeedFacets
import com.watchjobs.matching.FeedFilter
import com.watchjobs.matching.FeedResponse
import com.watchjobs.matching.FeedService
import com.watchjobs.matching.FeedSort
import com.watchjobs.matching.InteractionRequest
import com.watchjobs.matching.InteractionType
import com.watchjobs.matching.JobFacetQueries
import com.watchjobs.matching.JobSkillFacet
import com.watchjobs.matching.RefreshResponse
import com.watchjobs.matching.SkillMatch
import com.watchjobs.security.AppUserDetails
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// Busca no corpus (não consulta fonte externa), feed por nota, detalhe, interações e "salvar do corpus".
@RestController
@RequestMapping("/jobs")
class JobController(
    private val service: JobCorpusService,
    private val feed: FeedService,
    private val facets: JobFacetQueries
) {

    // Filtros repetidos (workMode=REMOTE&workMode=HYBRID) ou separados por vírgula; todos opcionais.
    @GetMapping("/feed")
    fun feed(
        @AuthenticationPrincipal principal: AppUserDetails,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) workMode: List<WorkMode>?,
        @RequestParam(required = false) level: List<ExperienceLevel>?,
        @RequestParam(required = false) jobType: List<JobType>?,
        @RequestParam(required = false) salaryMin: Double?,
        @RequestParam(required = false) salaryMax: Double?,
        @RequestParam(required = false) skill: List<Long>?,
        @RequestParam(defaultValue = "ANY") skillMatch: SkillMatch,
        @RequestParam(required = false) minScore: Double?,
        @RequestParam(defaultValue = "SCORE") sort: FeedSort,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam allParams: MultiValueMap<String, String>
    ): FeedResponse {
        if (salaryMin != null && salaryMax != null && salaryMin > salaryMax) {
            throw BusinessException("O salário mínimo não pode ser maior que o máximo")
        }
        return feed.feed(
            principal.id,
            FeedFilter(
                q = q, workModes = workMode.orEmpty(), levels = level.orEmpty(), jobTypes = jobType.orEmpty(),
                salaryMin = salaryMin, salaryMax = salaryMax, skillIds = skill.orEmpty(), skillMatch = skillMatch,
                roleFamilies = allParams["roleFamily"].orEmpty(), countries = allParams["country"].orEmpty(),
                states = allParams["state"].orEmpty(), cities = allParams["city"].orEmpty(), companies = allParams["company"].orEmpty(),
                minScore = minScore, sort = sort, page = page, size = size
            )
        )
    }

    @GetMapping("/facets")
    fun facets(): FeedFacets = facets.facets()

    @GetMapping("/skills")
    fun skills(
        @RequestParam(required = false) q: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int
    ): List<JobSkillFacet> = facets.skills(q, page.coerceAtLeast(0), size.coerceIn(1, 50))

    @PostMapping("/refresh")
    fun refresh(@AuthenticationPrincipal principal: AppUserDetails): RefreshResponse = feed.refresh(principal.id)

    @PostMapping("/{id}/interactions")
    fun interact(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Long,
        @RequestBody request: InteractionRequest
    ): ResponseEntity<Void> {
        feed.interact(principal.id, id, request.type ?: throw BusinessException("Informe o tipo da interação"))
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{id}/interactions/{type}")
    fun removeInteraction(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Long,
        @PathVariable type: InteractionType
    ): ResponseEntity<Void> {
        feed.removeInteraction(principal.id, id, type)
        return ResponseEntity.noContent().build()
    }

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
