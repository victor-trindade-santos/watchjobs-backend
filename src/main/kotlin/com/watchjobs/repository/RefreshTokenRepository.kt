package com.watchjobs.repository

import com.watchjobs.entity.RefreshToken
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByTokenHash(tokenHash: String): RefreshToken?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken token set token.revokedAt = :now where token.user.id = :usersId and token.revokedAt is null")
    fun revokeByUsersId(@Param("usersId") usersId: Int, @Param("now") now: OffsetDateTime): Int

    @Modifying
    @Query("update RefreshToken token set token.revokedAt = :now where token.familyId = :familyId and token.revokedAt is null")
    fun revokeByFamilyId(@Param("familyId") familyId: UUID, @Param("now") now: OffsetDateTime): Int
}