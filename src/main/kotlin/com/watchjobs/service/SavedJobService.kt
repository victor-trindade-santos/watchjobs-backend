package com.watchjobs.service

import com.watchjobs.dto.request.SavedJobRequest
import com.watchjobs.dto.response.SavedJobResponse
import com.watchjobs.entity.SavedJob
import com.watchjobs.entity.enums.SavedJobStatus
import com.watchjobs.exception.BusinessException
import com.watchjobs.exception.ConflictException
import com.watchjobs.exception.NotFoundException
import com.watchjobs.jobs.JobSkillResponse
import com.watchjobs.mapper.SavedJobMapper
import com.watchjobs.repository.ApplicationRepository
import com.watchjobs.repository.SavedJobRepository
import com.watchjobs.repository.UserRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.LocalDateTime

@Service
class SavedJobService(
    private val savedJobRepository: SavedJobRepository,
    private val applicationRepository: ApplicationRepository,
    private val userRepository: UserRepository,
    private val savedJobMapper: SavedJobMapper,
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper
) {
    @Transactional
    fun create(userId: Int, request: SavedJobRequest): SavedJobResponse {
        validateSalaryRange(request)
        val user = userRepository.getReferenceById(userId)
        val savedJob = savedJobRepository.save(savedJobMapper.toEntity(request, user))
        return savedJobMapper.toResponse(savedJob)
    }

    @Transactional(readOnly = true)
    fun findAll(userId: Int, q: String?): List<SavedJobResponse> {
        val savedJobs = if (q.isNullOrBlank()) {
            savedJobRepository.findAllByUserIdOrderBySavedAtDesc(userId)
        } else {
            savedJobRepository.search(userId, q.trim())
        }
        return savedJobs.map { savedJobMapper.toResponse(it) }
    }

    @Transactional(readOnly = true)
    fun findById(userId: Int, id: Long): SavedJobResponse =
        withJobContent(savedJobMapper.toResponse(findOwned(userId, id)))

    // full_description e requirements_snapshot não são mapeados na entidade (o snapshot é jsonb).
    private fun withJobContent(response: SavedJobResponse): SavedJobResponse {
        val (description, snapshotJson) = jdbc.sql(
            "SELECT full_description, CAST(requirements_snapshot AS text) AS snapshot FROM core.saved_job WHERE saved_job_id = :id"
        )
            .param("id", response.id)
            .query { rs, _ -> rs.getString("full_description") to rs.getString("snapshot") }
            .optional()
            .orElse(null) ?: return response
        @Suppress("UNCHECKED_CAST")
        val snapshot = snapshotJson
            ?.let { runCatching { mapper.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull() }
            .orEmpty()
        val skills = (snapshot["skills"] as? List<*>).orEmpty().mapNotNull { item ->
            val skill = item as? Map<*, *> ?: return@mapNotNull null
            val name = skill["name"] as? String ?: return@mapNotNull null
            JobSkillResponse(name, skill["required"] as? Boolean ?: true)
        }
        return response.copy(
            fullDescription = description,
            skills = skills,
            sourceName = snapshot["source"] as? String,
            roleFamily = snapshot["roleFamily"] as? String,
            rawLocation = snapshot["rawLocation"] as? String
        )
    }

    @Transactional
    fun update(userId: Int, id: Long, request: SavedJobRequest): SavedJobResponse {
        validateSalaryRange(request)
        val savedJob = findOwned(userId, id)
        val application = applicationRepository.findBySavedJobId(id)

        if (application != null && request.status in STATUSES_WITHOUT_APPLICATION) {
            throw ConflictException("Exclua a candidatura para voltar a vaga para Salva ou Planejando se candidatar")
        }

        val statusChanged = savedJob.status != request.status
        savedJobMapper.updateEntity(savedJob, request)

        if (statusChanged) {
            savedJob.statusUpdatedAt = LocalDateTime.now()
            application?.status = request.status.name
        }
        return savedJobMapper.toResponse(savedJobRepository.save(savedJob))
    }

    // A candidatura é apagada pelo banco (ON DELETE CASCADE da V2).
    @Transactional
    fun delete(userId: Int, id: Long) {
        savedJobRepository.delete(findOwned(userId, id))
    }

    private fun findOwned(userId: Int, id: Long): SavedJob =
        savedJobRepository.findByIdAndUserId(id, userId)
            ?: throw NotFoundException("Vaga não encontrada")

    private fun validateSalaryRange(request: SavedJobRequest) {
        val min = request.salaryMin
        val max = request.salaryMax
        if (min != null && max != null && min > max) {
            throw BusinessException("Salário mínimo não pode ser maior que o salário máximo")
        }
    }

    companion object {
        val STATUSES_WITHOUT_APPLICATION = setOf(SavedJobStatus.SAVED, SavedJobStatus.PLANNING_TO_APPLY)
    }
}
