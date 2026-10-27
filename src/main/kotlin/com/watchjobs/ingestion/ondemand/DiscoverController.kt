package com.watchjobs.ingestion.ondemand

import com.watchjobs.security.AppUserDetails
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/jobs/discover")
class DiscoverController(private val service: OnDemandSearchService) {

    @PostMapping
    fun discover(@AuthenticationPrincipal principal: AppUserDetails, @Valid @RequestBody request: DiscoverRequest): DiscoverResponse =
        service.discover(principal.id, request)

    @GetMapping("/{id}")
    fun status(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Long): DiscoverResponse =
        service.status(principal.id, id)
}
