package com.watchjobs.contact

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime

data class ContactRequest(
    @field:Size(max = 150, message = "Nome deve ter no máximo 150 caracteres")
    val contactName: String? = null,

    @field:Size(max = 100, message = "Função deve ter no máximo 100 caracteres")
    val contactRole: String? = null,

    @field:Email(message = "E-mail inválido")
    @field:Size(max = 255, message = "E-mail deve ter no máximo 255 caracteres")
    val email: String? = null,

    @field:Size(max = 30, message = "Telefone deve ter no máximo 30 caracteres")
    val phone: String? = null,

    @field:Size(max = 2048, message = "Link deve ter no máximo 2048 caracteres")
    val socialUrl: String? = null,

    @field:Size(max = 4000, message = "Notas devem ter no máximo 4000 caracteres")
    val notes: String? = null
) {
    // O banco exige ao menos um destes quatro campos (saved_job_contact_has_data_check).
    @AssertTrue(message = "Informe ao menos nome, e-mail, telefone ou link")
    fun isIdentifiable(): Boolean =
        listOf(contactName, email, phone, socialUrl).any { !it.isNullOrBlank() }
}

data class ContactResponse(
    val id: Long,
    val contactName: String?,
    val contactRole: String?,
    val email: String?,
    val phone: String?,
    val socialUrl: String?,
    val notes: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime
)
