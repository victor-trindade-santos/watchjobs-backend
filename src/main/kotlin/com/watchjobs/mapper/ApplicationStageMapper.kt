package com.watchjobs.mapper

import com.watchjobs.dto.response.ApplicationStageResponse
import com.watchjobs.entity.ApplicationStage
import org.springframework.stereotype.Component

@Component
class ApplicationStageMapper {
    fun toResponse(entity: ApplicationStage): ApplicationStageResponse = ApplicationStageResponse(
        id = entity.id ?: error("Etapa sem id"),
        name = entity.name
    )
}
