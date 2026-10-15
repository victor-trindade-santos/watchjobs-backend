package com.watchjobs.repository

import com.watchjobs.entity.Application
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ApplicationRepository : JpaRepository<Application, Int> {

    fun findAllByUserIdOrderByStartDateDesc(userId: Int): List<Application>

    fun findByIdAndUserId(id: Int, userId: Int): Application?

    fun findBySavedJobId(savedJobId: Long): Application?

    fun existsBySavedJobId(savedJobId: Long): Boolean

    @Query(
        """
            SELECT a FROM Application a
            WHERE a.user.id = :userId
               AND (
                lower(a.notes) LIKE lower(concat('%', :q, '%'))
                or lower(a.feedback) LIKE lower(concat('%', :q, '%'))
                or lower(a.savedJob.positionRole) LIKE lower(concat('%', :q, '%'))
                or lower(a.savedJob.organizationName) LIKE lower(concat('%', :q, '%'))
                )
            order by a.startDate desc
        """
    )
    fun search(userId: Int, q: String): List<Application>
}    