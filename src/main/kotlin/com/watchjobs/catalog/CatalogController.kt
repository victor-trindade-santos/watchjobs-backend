package com.watchjobs.catalog

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/catalog")
class CatalogController(private val queries: CatalogQueries) {

    @GetMapping("/skills")
    fun skills(@RequestParam(required = false) q: String?): List<SkillOption> = queries.skills(q)

    @GetMapping("/organizations")
    fun organizations(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) type: String?
    ): List<OrganizationOption> = queries.organizations(q, type?.uppercase())

    @GetMapping("/locations")
    fun locations(@RequestParam(required = false) q: String?): List<LocationOption> = queries.locations(q)

    @GetMapping("/languages")
    fun languages(): List<LanguageOption> = queries.languages()

    @GetMapping("/educations")
    fun educations(): List<EducationOption> = queries.educations()

    @GetMapping("/role-families")
    fun roleFamilies(): List<RoleFamilyOption> = queries.roleFamilies()
}
