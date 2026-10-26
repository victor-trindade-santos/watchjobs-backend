package com.watchjobs.repository

import com.watchjobs.entity.PasswordResetCode
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

interface PasswordResetCodeRepository : JpaRepository<PasswordResetCode, Long> {
    fun countByUser_IdAndCreatedAtGreaterThanEqual(usersId: Int, since: OffsetDateTime): Long

    fun findFirstByUser_IdOrderByCreatedAtDescIdDesc(usersId: Int): PasswordResetCode?

    fun findFirstByUser_IdAndUsedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDescIdDesc(usersId: Int): PasswordResetCode?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetCode code set code.revokedAt = :now where code.user.id = :usersId and code.usedAt is null and code.revokedAt is null")
    fun revokeActiveByUsersId(@Param("usersId") usersId: Int, @Param("now") now: OffsetDateTime): Int
}