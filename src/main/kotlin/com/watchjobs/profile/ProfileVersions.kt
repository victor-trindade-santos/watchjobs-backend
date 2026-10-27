package com.watchjobs.profile

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

// profile_version mora em core.userpreference. A linha nasce na primeira mudança de perfil, com a
// versão 2 (antes dela a versão "vale" 1), para cada mudança subir exatamente uma vez.
@Repository
class ProfileVersions(private val jdbc: JdbcClient) {

    fun current(userId: Int): Int =
        jdbc.sql("SELECT profile_version FROM core.userpreference WHERE users_id = :userId")
            .param("userId", userId)
            .query(Int::class.java)
            .optional()
            .orElse(1)

    fun bump(userId: Int): Int =
        jdbc.sql(
            """
            INSERT INTO core.userpreference (users_id, preferred_work_mode, preferred_roles, profile_version)
            VALUES (:userId, 'ANY', '{}', 2)
            ON CONFLICT (users_id) DO UPDATE SET profile_version = core.userpreference.profile_version + 1
            RETURNING profile_version
            """
        )
            .param("userId", userId)
            .query(Int::class.java)
            .single()
}
