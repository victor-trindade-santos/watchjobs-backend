package com.watchjobs.service

import com.watchjobs.dto.response.ApplicationStageResponse
import com.watchjobs.mapper.ApplicationStageMapper
import com.watchjobs.repository.ApplicationStageRepository
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ApplicationStageService(
    private val applicationStageRepository: ApplicationStageRepository,
    private val applicationStageMapper: ApplicationStageMapper
) {
    @Transactional(readOnly = true)
    fun findAll(q: String?): List<ApplicationStageResponse> {
        val stages = if (q.isNullOrBlank()) {
            applicationStageRepository.findAll(Sort.by("id"))
        } else {
            applicationStageRepository.searchByName(q.trim())
        }
        return stages.map { applicationStageMapper.toResponse(it) }
    }
}
