package com.watchjobs.repository

import com.watchjobs.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface UserRepository : JpaRepository<User, Int> {
    fun findByEmailIgnoreCase(email: String): User?

    fun existsByEmailIgnoreCase(email: String): Boolean

    @Query(
        """
            SELECT u FROM User u
            WHERE lower(u.firstName) LIKE lower(concat('%', :q, '%'))
            OR lower(u.lastName) LIKE lower(concat('%', :q, '%'))
        """
    )

    fun searchByName(q: String): List<User>
}