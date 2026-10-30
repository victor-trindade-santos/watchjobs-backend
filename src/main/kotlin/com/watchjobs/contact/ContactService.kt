package com.watchjobs.contact

import com.watchjobs.exception.NotFoundException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.OffsetDateTime

// Contatos do processo (recrutador, gestor, indicação) de uma vaga salva. O dono vem sempre do token.
@Service
class ContactService(private val jdbc: JdbcClient) {

    @Transactional(readOnly = true)
    fun list(userId: Int, savedJobId: Long): List<ContactResponse> {
        requireOwned(userId, savedJobId)
        return jdbc.sql("$SELECT WHERE saved_job_id = :savedJobId ORDER BY created_at, saved_job_contact_id")
            .param("savedJobId", savedJobId).query { rs, _ -> row(rs) }.list()
    }

    @Transactional
    fun create(userId: Int, savedJobId: Long, request: ContactRequest): ContactResponse {
        requireOwned(userId, savedJobId)
        val id = jdbc.sql(
            """
            INSERT INTO core.saved_job_contact (saved_job_id, contact_name, contact_role, email, phone, social_url, notes)
            VALUES (:savedJobId, :name, :role, :email, :phone, :socialUrl, :notes)
            RETURNING saved_job_contact_id
            """
        ).param("savedJobId", savedJobId).bind(request).query(Long::class.java).single()
        return find(savedJobId, id)
    }

    @Transactional
    fun update(userId: Int, savedJobId: Long, id: Long, request: ContactRequest): ContactResponse {
        requireOwned(userId, savedJobId)
        val updated = jdbc.sql(
            """
            UPDATE core.saved_job_contact
               SET contact_name = :name, contact_role = :role, email = :email, phone = :phone,
                   social_url = :socialUrl, notes = :notes
             WHERE saved_job_contact_id = :id AND saved_job_id = :savedJobId
            """
        ).param("id", id).param("savedJobId", savedJobId).bind(request).update()
        if (updated == 0) throw NotFoundException("Contato não encontrado")
        return find(savedJobId, id)
    }

    @Transactional
    fun delete(userId: Int, savedJobId: Long, id: Long) {
        requireOwned(userId, savedJobId)
        val deleted = jdbc.sql("DELETE FROM core.saved_job_contact WHERE saved_job_contact_id = :id AND saved_job_id = :savedJobId")
            .param("id", id).param("savedJobId", savedJobId).update()
        if (deleted == 0) throw NotFoundException("Contato não encontrado")
    }

    private fun requireOwned(userId: Int, savedJobId: Long) {
        val owned = jdbc.sql("SELECT EXISTS (SELECT 1 FROM core.saved_job WHERE saved_job_id = :id AND users_id = :userId)")
            .param("id", savedJobId).param("userId", userId).query(Boolean::class.java).single()
        if (!owned) throw NotFoundException("Vaga não encontrada")
    }

    private fun find(savedJobId: Long, id: Long): ContactResponse =
        jdbc.sql("$SELECT WHERE saved_job_id = :savedJobId AND saved_job_contact_id = :id")
            .param("savedJobId", savedJobId).param("id", id)
            .query { rs, _ -> row(rs) }.optional().orElseThrow { NotFoundException("Contato não encontrado") }

    // Texto em branco vira nulo, para o CHECK do banco e a resposta ficarem coerentes.
    private fun JdbcClient.StatementSpec.bind(request: ContactRequest): JdbcClient.StatementSpec =
        param("name", clean(request.contactName)).param("role", clean(request.contactRole))
            .param("email", clean(request.email)).param("phone", clean(request.phone))
            .param("socialUrl", clean(request.socialUrl)).param("notes", clean(request.notes))

    private fun clean(value: String?): String? = value?.trim()?.ifEmpty { null }

    private fun row(rs: ResultSet) = ContactResponse(
        id = rs.getLong("saved_job_contact_id"),
        contactName = rs.getString("contact_name"),
        contactRole = rs.getString("contact_role"),
        email = rs.getString("email"),
        phone = rs.getString("phone"),
        socialUrl = rs.getString("social_url"),
        notes = rs.getString("notes"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java)
    )

    private companion object {
        const val SELECT = """
            SELECT saved_job_contact_id, contact_name, contact_role, email, phone, social_url, notes, created_at, updated_at
              FROM core.saved_job_contact"""
    }
}
