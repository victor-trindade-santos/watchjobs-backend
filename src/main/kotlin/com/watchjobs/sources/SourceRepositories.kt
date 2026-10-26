package com.watchjobs.sources

import org.springframework.data.jpa.repository.JpaRepository

interface JobSourceRepository : JpaRepository<JobSource, Long> {
    fun findByCode(code: String): JobSource?
}

interface SourceCompanyRepository : JpaRepository<SourceCompany, Long> {
    fun findAllByJobSourceIdAndActiveTrue(jobSourceId: Long): List<SourceCompany>
}
