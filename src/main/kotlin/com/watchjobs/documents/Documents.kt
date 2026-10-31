package com.watchjobs.documents

import com.watchjobs.catalog.TextNormalizer
import com.watchjobs.exception.BusinessException
import com.watchjobs.exception.NotFoundException
import com.watchjobs.exception.TooManyRequestsException
import com.watchjobs.profile.ProfileService
import com.watchjobs.security.AppUserDetails
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.Base64

enum class VersionSource { AI, EDIT, RESTORE }

data class GenerateDocumentRequest(
    @field:NotNull(message = "Escolha a vaga") val savedJobId: Long? = null,
    @field:NotNull(message = "Escolha o tipo de documento") val type: DocumentType? = null,
    @field:Size(max = 1000, message = "As instruções devem ter no máximo 1000 caracteres") val instructions: String? = null
)

data class EditDocumentRequest(
    @field:NotBlank(message = "O documento não pode ficar vazio")
    @field:Size(max = DocumentValidator.MAX_CONTENT, message = "O documento deve ter no máximo 60000 caracteres")
    val content: String = ""
)

data class DocumentVersionSummary(
    val version: Int,
    val source: VersionSource,
    val model: String?,
    val warnings: List<String>,
    val length: Int,
    val createdAt: OffsetDateTime
)

data class DocumentSummary(
    val id: Long,
    val savedJobId: Long,
    val type: DocumentType,
    val title: String,
    val positionRole: String,
    val organizationName: String,
    val currentVersion: Int,
    val updatedAt: OffsetDateTime
)

data class DocumentDetail(
    val id: Long,
    val savedJobId: Long,
    val type: DocumentType,
    val title: String,
    val positionRole: String,
    val organizationName: String,
    val currentVersion: Int,
    val content: String,
    val warnings: List<String>,
    val versions: List<DocumentVersionSummary>,
    val updatedAt: OffsetDateTime
)

data class VersionContent(val version: Int, val source: VersionSource, val content: String, val warnings: List<String>, val createdAt: OffsetDateTime)

data class ExportFile(val fileName: String, val mimeType: String, val base64: String)

data class BaseResume(val content: String)

data class DocumentsStatus(val enabled: Boolean, val dailyLimit: Int, val usedToday: Int)

// Documentos por vaga (F10): gera com IA a partir do perfil (currículo base) e da vaga salva, versiona,
// permite editar, restaurar e exportar. A chamada à IA fica fora de transação; só a gravação é transacional.
@Service
class DocumentService(
    private val jdbc: JdbcClient,
    private val profiles: ProfileService,
    private val generator: TextGenerator,
    private val mapper: ObjectMapper,
    private val tx: TransactionTemplate,
    @Value("\${app.documents.daily-limit:20}") private val dailyLimit: Int
) {
    fun status(userId: Int) = DocumentsStatus(generator.enabled, dailyLimit, usedToday(userId))

    fun list(userId: Int, savedJobId: Long?): List<DocumentSummary> =
        jdbc.sql(
            """
            $SUMMARY_SELECT
             WHERE d.users_id = :userId AND (CAST(:jobId AS int8) IS NULL OR d.saved_job_id = CAST(:jobId AS int8))
             ORDER BY d.updated_at DESC
            """
        ).param("userId", userId).param("jobId", savedJobId).query { rs, _ ->
            DocumentSummary(
                id = rs.getLong("generated_document_id"),
                savedJobId = rs.getLong("saved_job_id"),
                type = DocumentType.valueOf(rs.getString("doc_type")),
                title = rs.getString("title"),
                positionRole = rs.getString("position_role"),
                organizationName = rs.getString("organization_name"),
                currentVersion = rs.getInt("current_version"),
                updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java)
            )
        }.list()

    fun generate(userId: Int, request: GenerateDocumentRequest): DocumentDetail {
        val type = request.type ?: throw BusinessException("Escolha o tipo de documento")
        val job = job(userId, request.savedJobId ?: throw BusinessException("Escolha a vaga"))
        val profile = profiles.profile(userId)
        if (profile.experiences.isEmpty() && profile.personal.objective.isNullOrBlank() && profile.skills.isEmpty()) {
            throw BusinessException("Complete o perfil (resumo, experiências e habilidades) antes de gerar documentos.")
        }
        if (usedToday(userId) >= dailyLimit) {
            throw TooManyRequestsException("Você atingiu o limite de $dailyLimit gerações por dia. Edite as versões existentes ou tente amanhã.")
        }
        val profileText = ContextBudget.profileText(profile)
        val generated = generator.generate(
            PromptBuilder.system(type),
            PromptBuilder.user(type, profileText, ContextBudget.jobText(job), request.instructions),
            PromptBuilder.maxTokens(type)
        )
        val content = DocumentValidator.clean(generated.content)
        if (content.isBlank()) throw BusinessException("A IA não devolveu conteúdo. Tente gerar novamente.")
        val warnings = buildList {
            if (job.description.isNullOrBlank() && job.requirements.isNullOrBlank()) {
                add("A vaga não tem descrição salva: o documento usou só o cargo e a empresa. Cole a descrição na vaga para um resultado melhor.")
            }
            addAll(DocumentValidator.warnings(content, DocumentFormat.baseResume(profile), catalogSkills()))
        }
        val id = saveVersion(
            userId, job, type, content, VersionSource.AI,
            model = generated.model, instructions = request.instructions?.trim()?.ifEmpty { null },
            promptTokens = generated.promptTokens, completionTokens = generated.completionTokens, warnings = warnings
        )
        return detail(userId, id)
    }

    fun detail(userId: Int, id: Long): DocumentDetail {
        val summary = jdbc.sql("$SUMMARY_SELECT WHERE d.users_id = :userId AND d.generated_document_id = :id")
            .param("userId", userId).param("id", id)
            .query { rs, _ ->
                DocumentSummary(
                    id = rs.getLong("generated_document_id"),
                    savedJobId = rs.getLong("saved_job_id"),
                    type = DocumentType.valueOf(rs.getString("doc_type")),
                    title = rs.getString("title"),
                    positionRole = rs.getString("position_role"),
                    organizationName = rs.getString("organization_name"),
                    currentVersion = rs.getInt("current_version"),
                    updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java)
                )
            }.optional().orElseThrow { NotFoundException("Documento não encontrado") }
        val current = version(userId, id, summary.currentVersion)
        val versions = jdbc.sql(
            """
            SELECT version, source, model, warnings::text AS warnings, length(content) AS length, created_at
              FROM core.generated_document_version
             WHERE generated_document_id = :id
             ORDER BY version DESC
            """
        ).param("id", id).query { rs, _ ->
            DocumentVersionSummary(
                version = rs.getInt("version"),
                source = VersionSource.valueOf(rs.getString("source")),
                model = rs.getString("model"),
                warnings = warningsOf(rs.getString("warnings")),
                length = rs.getInt("length"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java)
            )
        }.list()
        return DocumentDetail(
            summary.id, summary.savedJobId, summary.type, summary.title, summary.positionRole, summary.organizationName,
            summary.currentVersion, current.content, current.warnings, versions, summary.updatedAt
        )
    }

    fun version(userId: Int, id: Long, version: Int): VersionContent =
        jdbc.sql(
            """
            SELECT v.version, v.source, v.content, v.warnings::text AS warnings, v.created_at
              FROM core.generated_document_version v
              JOIN core.generated_document d ON d.generated_document_id = v.generated_document_id
             WHERE d.users_id = :userId AND d.generated_document_id = :id AND v.version = :version
            """
        ).param("userId", userId).param("id", id).param("version", version).query { rs, _ ->
            VersionContent(
                rs.getInt("version"), VersionSource.valueOf(rs.getString("source")), rs.getString("content"),
                warningsOf(rs.getString("warnings")), rs.getObject("created_at", OffsetDateTime::class.java)
            )
        }.optional().orElseThrow { NotFoundException("Versão não encontrada") }

    fun edit(userId: Int, id: Long, request: EditDocumentRequest): DocumentDetail {
        val current = detail(userId, id)
        val content = request.content.replace("\r", "").trim()
        if (content.isEmpty()) throw BusinessException("O documento não pode ficar vazio")
        if (content == current.content) return current
        saveVersion(userId, job(userId, current.savedJobId), current.type, content, VersionSource.EDIT)
        return detail(userId, id)
    }

    fun restore(userId: Int, id: Long, version: Int): DocumentDetail {
        val current = detail(userId, id)
        val old = version(userId, id, version)
        if (version == current.currentVersion) return current
        saveVersion(userId, job(userId, current.savedJobId), current.type, old.content, VersionSource.RESTORE, warnings = old.warnings)
        return detail(userId, id)
    }

    fun delete(userId: Int, id: Long) {
        val deleted = jdbc.sql("DELETE FROM core.generated_document WHERE generated_document_id = :id AND users_id = :userId")
            .param("id", id).param("userId", userId).update()
        if (deleted == 0) throw NotFoundException("Documento não encontrado")
    }

    fun export(userId: Int, id: Long, format: ExportFormat, version: Int?): ExportFile {
        val document = detail(userId, id)
        val content = version?.let { version(userId, id, it).content } ?: document.content
        val prefix = if (document.type == DocumentType.RESUME) "curriculo" else "cover-letter"
        return file("$prefix-${slug(document.organizationName)}-${slug(document.positionRole)}", content, format)
    }

    fun baseResume(userId: Int): BaseResume = BaseResume(DocumentFormat.baseResume(profiles.profile(userId)))

    fun exportBase(userId: Int, format: ExportFormat): ExportFile = file("curriculo-base", baseResume(userId).content, format)

    private fun file(name: String, content: String, format: ExportFormat) =
        ExportFile("$name.${format.extension}", format.mimeType, Base64.getEncoder().encodeToString(DocumentExporter.export(content, format)))

    private fun slug(text: String): String =
        TextNormalizer.stripAccents(text).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "vaga" }

    private fun saveVersion(
        userId: Int,
        job: JobContext,
        type: DocumentType,
        content: String,
        source: VersionSource,
        model: String? = null,
        instructions: String? = null,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        warnings: List<String> = emptyList()
    ): Long = tx.execute {
        val title = (if (type == DocumentType.RESUME) "Currículo" else "Cover letter") + " — ${job.positionRole} (${job.organizationName})"
        val documentId = jdbc.sql(
            """
            INSERT INTO core.generated_document (users_id, saved_job_id, doc_type, title, current_version)
            VALUES (:userId, :jobId, :type, :title, 0)
            ON CONFLICT (saved_job_id, doc_type) DO UPDATE SET updated_at = now()
            RETURNING generated_document_id
            """
        ).param("userId", userId).param("jobId", job.savedJobId).param("type", type.name).param("title", title.take(300))
            .query(Long::class.java).single()
        jdbc.sql("SELECT generated_document_id FROM core.generated_document WHERE generated_document_id = :id FOR UPDATE")
            .param("id", documentId).query(Long::class.java).single()
        val next = jdbc.sql("SELECT COALESCE(max(version), 0) + 1 FROM core.generated_document_version WHERE generated_document_id = :id")
            .param("id", documentId).query(Int::class.java).single()
        jdbc.sql(
            """
            INSERT INTO core.generated_document_version (generated_document_id, version, content, source, model, instructions,
                prompt_tokens, completion_tokens, warnings)
            VALUES (:id, :version, :content, :source, :model, :instructions, :promptTokens, :completionTokens, CAST(:warnings AS jsonb))
            """
        )
            .param("id", documentId).param("version", next).param("content", content).param("source", source.name)
            .param("model", model).param("instructions", instructions).param("promptTokens", promptTokens)
            .param("completionTokens", completionTokens).param("warnings", mapper.writeValueAsString(warnings))
            .update()
        jdbc.sql("UPDATE core.generated_document SET current_version = :version, updated_at = now() WHERE generated_document_id = :id")
            .param("version", next).param("id", documentId).update()
        documentId
    } ?: throw IllegalStateException("Falha ao salvar o documento")

    private fun job(userId: Int, savedJobId: Long): JobContext =
        jdbc.sql(
            """
            SELECT saved_job_id, position_role, organization_name, location_city, location_state, location_country_code,
                   work_mode, experience_level, full_description, requirements_snapshot::text AS requirements
              FROM core.saved_job
             WHERE saved_job_id = :id AND users_id = :userId
            """
        ).param("id", savedJobId).param("userId", userId).query { rs, _ ->
            JobContext(
                savedJobId = rs.getLong("saved_job_id"),
                positionRole = rs.getString("position_role"),
                organizationName = rs.getString("organization_name"),
                location = listOfNotNull(rs.getString("location_city"), rs.getString("location_state"), rs.getString("location_country_code"))
                    .joinToString(", ").ifEmpty { null },
                workMode = rs.getString("work_mode"),
                experienceLevel = rs.getString("experience_level"),
                description = rs.getString("full_description"),
                requirements = requirementsText(rs.getString("requirements"))
            )
        }.optional().orElseThrow { NotFoundException("Vaga não encontrada") }

    // O snapshot guarda skills e requisitos da vaga; ids internos não interessam à IA.
    private fun requirementsText(json: String?): String? {
        if (json.isNullOrBlank()) return null
        val map = runCatching { mapper.readValue(json, Map::class.java) }.getOrNull() ?: return null
        return map.entries
            .filter { (key, value) -> value != null && !key.toString().endsWith("Id") && !key.toString().endsWith("_id") }
            .joinToString("; ") { (key, value) -> "$key: $value" }
            .take(2000)
            .ifBlank { null }
    }

    private fun usedToday(userId: Int): Int =
        jdbc.sql(
            """
            SELECT count(*) FROM core.generated_document_version v
              JOIN core.generated_document d ON d.generated_document_id = v.generated_document_id
             WHERE d.users_id = :userId AND v.source = 'AI' AND v.created_at >= date_trunc('day', now())
            """
        ).param("userId", userId).query(Int::class.java).single()

    private fun catalogSkills(): List<String> =
        jdbc.sql("SELECT skill_name FROM core.skill").query(String::class.java).list().filterNotNull()

    private fun warningsOf(json: String?): List<String> {
        if (json == null) return emptyList()
        return runCatching<List<String>> {
            val values: List<*> = mapper.readValue(json, List::class.java)
            val warnings = mutableListOf<String>()
            values.forEach { value ->
                if (value is String) warnings.add(value)
            }
            warnings
        }.getOrElse { emptyList() }
    }

    companion object {
        private const val SUMMARY_SELECT = """
            SELECT d.generated_document_id, d.saved_job_id, d.doc_type, d.title, d.current_version, d.updated_at,
                   sj.position_role, sj.organization_name
              FROM core.generated_document d
              JOIN core.saved_job sj ON sj.saved_job_id = d.saved_job_id
        """
    }
}

@RestController
@RequestMapping("/documents")
class DocumentController(private val service: DocumentService) {

    @GetMapping("/status")
    fun status(@AuthenticationPrincipal principal: AppUserDetails) = service.status(principal.id)

    @GetMapping
    fun list(@AuthenticationPrincipal principal: AppUserDetails, @RequestParam(required = false) savedJobId: Long?) =
        service.list(principal.id, savedJobId)

    @PostMapping("/generate")
    fun generate(@AuthenticationPrincipal principal: AppUserDetails, @Valid @RequestBody request: GenerateDocumentRequest) =
        service.generate(principal.id, request)

    @GetMapping("/base-resume")
    fun baseResume(@AuthenticationPrincipal principal: AppUserDetails) = service.baseResume(principal.id)

    @GetMapping("/base-resume/export")
    fun exportBase(@AuthenticationPrincipal principal: AppUserDetails, @RequestParam(defaultValue = "PDF") format: ExportFormat) =
        service.exportBase(principal.id, format)

    @GetMapping("/{id}")
    fun detail(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Long) = service.detail(principal.id, id)

    @PutMapping("/{id}")
    fun edit(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Long, @Valid @RequestBody request: EditDocumentRequest) =
        service.edit(principal.id, id, request)

    @GetMapping("/{id}/versions/{version}")
    fun version(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Long, @PathVariable version: Int) =
        service.version(principal.id, id, version)

    @PostMapping("/{id}/versions/{version}/restore")
    fun restore(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Long, @PathVariable version: Int) =
        service.restore(principal.id, id, version)

    @GetMapping("/{id}/export")
    fun export(
        @AuthenticationPrincipal principal: AppUserDetails,
        @PathVariable id: Long,
        @RequestParam(defaultValue = "PDF") format: ExportFormat,
        @RequestParam(required = false) version: Int?
    ) = service.export(principal.id, id, format, version)

    @DeleteMapping("/{id}")
    fun delete(@AuthenticationPrincipal principal: AppUserDetails, @PathVariable id: Long): ResponseEntity<Void> {
        service.delete(principal.id, id)
        return ResponseEntity.noContent().build()
    }
}
