package com.watchjobs.repository

import com.watchjobs.entity.SavedJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface SavedJobRepository : JpaRepository<SavedJob, Long> {

    fun findAllByUserIdOrderBySavedAtDesc(userId: Int): List<SavedJob>

    fun findByIdAndUserId(id: Long, userId: Int): SavedJob?

    @Query(
        """
            SELECT s FROM SavedJob s
            WHERE s.user.id = :userId
               AND (
                lower(s.positionRole) LIKE lower(concat('%', :q, '%'))
                OR lower(s.organizationName) LIKE lower(concat('%', :q, '%'))                   
               )
            order by s.savedAt desc
        """
    )   

    fun search(userId: Int, q: String): List<SavedJob>
}
