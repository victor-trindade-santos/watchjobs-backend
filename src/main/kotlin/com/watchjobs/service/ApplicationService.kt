package com.watchjobs.service

import com.watchjobs.board.ApplicationEventService
import com.watchjobs.board.BoardColumnMapper
import com.watchjobs.board.EventType
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
    private val applicationMapper: ApplicationMapper,
    private val events: ApplicationEventService
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

        val previousStatus = savedJob.status
        syncSavedJobStatus(savedJob, request.status)
        val application = applicationRepository.save(
            applicationMapper.toEntity(request, user, savedJob, stage)
        )
        events.record(request.savedJobId, userId, EventType.APPLICATION_CREATED, previousStatus.name, request.status,
            application.id, toStageId = stage.id)
        moveCardIfColumnChanged(request.savedJobId, userId, previousStatus, savedJob.status)
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
        val previousStage = application.stage?.id
        val previousStatus = savedJob.status

        applicationMapper.updateEntity(application, request, stage)
        syncSavedJobStatus(savedJob, request.status)
        val savedJobId = requireNotNull(savedJob.id)
        if (previousStage != stage.id) {
            events.record(savedJobId, userId, EventType.STAGE_CHANGED, applicationId = application.id,
                fromStageId = previousStage, toStageId = stage.id)
        }
        if (previousStatus != savedJob.status) {
            events.record(savedJobId, userId, EventType.STATUS_CHANGED, previousStatus.name, savedJob.status.name, application.id)
            moveCardIfColumnChanged(savedJobId, userId, previousStatus, savedJob.status)
        }
        return applicationMapper.toResponse(applicationRepository.save(application))
    }

    @Transactional
    fun delete(userId: Int, id: Int) {
        val application = findOwned(userId, id)
        val savedJob = application.savedJob ?: error("Candidatura sem vaga")
        val previousStatus = savedJob.status
        val savedJobId = requireNotNull(savedJob.id)
        events.record(savedJobId, userId, EventType.APPLICATION_DELETED, previousStatus.name, SavedJobStatus.SAVED.name, application.id)
        applicationRepository.delete(application)
        savedJob.status = SavedJobStatus.SAVED
        savedJob.statusUpdatedAt = LocalDateTime.now()
        savedJobRepository.save(savedJob)
        moveCardIfColumnChanged(savedJobId, userId, previousStatus, SavedJobStatus.SAVED)
    }

    private fun moveCardIfColumnChanged(savedJobId: Long, userId: Int, from: SavedJobStatus, to: SavedJobStatus) {
        if (BoardColumnMapper.columnOf(from) != BoardColumnMapper.columnOf(to)) events.appendToColumn(savedJobId, userId, to)
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
