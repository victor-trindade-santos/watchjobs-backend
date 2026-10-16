package com.watchjobs.controller

import com.watchjobs.dto.response.ApplicationStageResponse
import com.watchjobs.service.ApplicationStageService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/application-stages")
class ApplicationStageController(
    private val applicationStageService: ApplicationStageService
) {
    @GetMapping
    fun findAll(@RequestParam(required = false) q: String?): List<ApplicationStageResponse> =
        applicationStageService.findAll(q)
}
