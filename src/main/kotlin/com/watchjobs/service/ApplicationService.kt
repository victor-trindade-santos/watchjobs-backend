package com.watchjobs.service

import com.watchjobs.dto.request.ApplicationRequest
import com.watchjobs.dto.response.ApplicationResponse
import com.watchjobs.entity.Application
import com.watchjobs.entity.ApplicationStage
import com.watchjobs.entity.SavedJob
import com.watchjobs.entity.enums.SavedJobStatus
import com.watchjobs.exception.BusinessException
import com.watchjobs.exception.ConflictException
import com.watchjobs.exception.NotFoundException
import com.watchjobs.mapper.ApplicationMapper
import com.watchjobs.repository.ApplicationRepository
import com.watchjobs.repository.ApplicationStageRepository
import com.watchjobs.repository.SavedJobRepository
import com.watchjobs.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class ApplicationService(
    private val applicationRepository: ApplicationRepository,
    private val savedJobRepository: SavedJobRepository,
    private val applicationStageRepository: ApplicationStageRepository,
    private val userRepository: UserRepository,
    private val applicationMapper: ApplicationMapper
) {
    @Transactional
    fun create(userId: Int, request: ApplicationRequest): ApplicationResponse {
        validateDates(request)
        val savedJob = savedJobRepository.findByIdAndUserId(request.savedJobId, userId)
            ?: throw NotFoundException("Vaga não encontrada")
        if (applicationRepository.existsBySavedJobId(request.savedJobId)) {
            throw ConflictException("Esta vaga já possui uma candidatura")
        }
        val stage = findStage(request.stageId)
        val user = userRepository.getReferenceById(userId)

        syncSavedJobStatus(savedJob, request.status)
        val application = applicationRepository.save(
            applicationMapper.toEntity(request, user, savedJob, stage)
        )
        return applicationMapper.toResponse(application)
    }

    @Transactional(readOnly = true)
    fun findAll(userId: Int, q: String?): List<ApplicationResponse> {
        val applications = if (q.isNullOrBlank()) {
            applicationRepository.findAllByUserIdOrderByStartDateDesc(userId)
        } else {
            applicationRepository.search(userId, q.trim())
        }
        return applications.map { applicationMapper.toResponse(it) }
    }

    @Transactional(readOnly = true)
    fun findById(userId: Int, id: Int): ApplicationResponse =
        applicationMapper.toResponse(findOwned(userId, id))

    @Transactional
    fun update(userId: Int, id: Int, request: ApplicationRequest): ApplicationResponse {
        validateDates(request)
        val application = findOwned(userId, id)
        val savedJob = application.savedJob ?: error("Candidatura sem vaga")
        if (savedJob.id != request.savedJobId) {
            throw BusinessException("Não é possível trocar a vaga de uma candidatura")
        }
        val stage = findStage(request.stageId)

        applicationMapper.updateEntity(application, request, stage)
        syncSavedJobStatus(savedJob, request.status)
        return applicationMapper.toResponse(applicationRepository.save(application))
    }

    @Transactional
    fun delete(userId: Int, id: Int) {
        val application = findOwned(userId, id)
        val savedJob = application.savedJob ?: error("Candidatura sem vaga")
        applicationRepository.delete(application)
        savedJob.status = SavedJobStatus.SAVED
        savedJob.statusUpdatedAt = LocalDateTime.now()
        savedJobRepository.save(savedJob)
    }

    private fun findOwned(userId: Int, id: Int): Application =
        applicationRepository.findByIdAndUserId(id, userId)
            ?: throw NotFoundException("Candidatura não encontrada")

    private fun findStage(stageId: Int): ApplicationStage =
        applicationStageRepository.findById(stageId)
            .orElseThrow { NotFoundException("Etapa da candidatura não encontrada") }

    private fun syncSavedJobStatus(savedJob: SavedJob, status: String) {
        val newStatus = SavedJobStatus.valueOf(status)
        if (savedJob.status != newStatus) {
            savedJob.status = newStatus
            savedJob.statusUpdatedAt = LocalDateTime.now()
            savedJobRepository.save(savedJob)
        }
    }

    private fun validateDates(request: ApplicationRequest) {
        val endDate = request.endDate
        if (endDate != null && endDate.isBefore(request.applicationDate)) {
            throw BusinessException("Data de término não pode ser anterior à data da candidatura")
        }
    }
}
