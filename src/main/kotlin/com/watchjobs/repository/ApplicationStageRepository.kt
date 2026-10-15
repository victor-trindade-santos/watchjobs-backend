package com.watchjobs.repository

import com.watchjobs.entity.ApplicationStage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ApplicationStageRepository : JpaRepository<ApplicationStage, Int> {

    @Query(
        """
            SELECT s FROM ApplicationStage s
            WHERE lower(s.name) LIKE lower(concat('%', :q, '%'))
            order by s.name
        """
    )
    fun searchByName(q: String): List<ApplicationStage>
}            