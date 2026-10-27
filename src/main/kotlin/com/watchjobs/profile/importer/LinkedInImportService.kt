package com.watchjobs.profile.importer

import com.watchjobs.catalog.NameNormalizer
import com.watchjobs.catalog.TextNormalizer
import com.watchjobs.classification.RuleSet
import com.watchjobs.exception.BusinessException
import com.watchjobs.exception.ConflictException
import com.watchjobs.exception.NotFoundException
import com.watchjobs.ingestion.store.CatalogStore
import com.watchjobs.ingestion.normalize.SkillExtractor
import com.watchjobs.profile.DataSource
import com.watchjobs.profile.EducationData
import com.watchjobs.profile.ExperienceRequest
import com.watchjobs.profile.ExperienceType
import com.watchjobs.profile.LocationInput
import com.watchjobs.profile.PersonalRequest
import com.watchjobs.profile.ProficiencyLevel
import com.watchjobs.profile.ProfileChangeTracker
import com.watchjobs.profile.ProfileRepository
import com.watchjobs.profile.ProfileService
import com.watchjobs.profile.SkillLevel
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.OffsetDateTime

enum class ItemStatus { NEW, EXISTS, CONFLICT, UNRECOGNIZED, INVALID }

data class ExperienceItem(
    val ref: String,
    val status: ItemStatus,
    val jobTitle: String,
    val organizationName: String,
    val location: String?,
    val description: String?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val current: Boolean
)

data class EducationItem(
    val ref: String,
    val status: ItemStatus,
    val institution: String,
    val degree: String?,
    val educationId: Long?,
    val educationLevel: String?,
    val fieldOfStudy: String?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val current: Boolean
)

data class SkillItem(val ref: String, val status: ItemStatus, val name: String, val skillId: Long?)

data class LanguageItem(
    val ref: String,
    val status: ItemStatus,
    val name: String,
    val languageId: Long?,
    val proficiencyLevel: ProficiencyLevel
)

data class ProfileItem(val headline: String?, val location: String?)

data class ImportPreview(
    val experiences: List<ExperienceItem> = emptyList(),
    val educations: List<EducationItem> = emptyList(),
    val skills: List<SkillItem> = emptyList(),
    val languages: List<LanguageItem> = emptyList(),
    val profile: ProfileItem? = null
) {
    fun total(): Int = experiences.size + educations.size + skills.size + languages.size
}

data class ImportResponse(
    val id: Long,
    val status: String,
    val importSource: String,
    val itemsTotal: Int,
    val itemsApplied: Int,
    val createdAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val appliedAt: OffsetDateTime?,
    val preview: ImportPreview
)

data class ApplyImportRequest(val refs: List<String>? = null)

// Prévia e aplicação da importação do LinkedIn (3.12.2). O arquivo nunca é gravado: só a prévia
// (7 dias) e o SHA-256 do arquivo. Aplicar nunca sobrescreve o que o usuário digitou (MANUAL).
@Service
class LinkedInImportService(
    private val jdbc: JdbcClient,
    private val repository: ProfileRepository,
    private val profileService: ProfileService,
    private val catalog: CatalogStore,
    private val tracker: ProfileChangeTracker,
    private val mapper: ObjectMapper
) {
    private val reader = LinkedInZipReader()

    @Transactional
    fun preview(userId: Int, bytes: ByteArray): ImportResponse {
        if (bytes.isEmpty()) throw BusinessException("Envie o arquivo ZIP exportado do LinkedIn")
        checkSize(bytes)
        return savePreview(userId, bytes, reader.read(bytes), "LINKEDIN_ZIP")
    }

    // Currículo em PDF, DOCX ou TXT: o texto vira o mesmo formato do ZIP e segue a mesma prévia.
    @Transactional
    fun previewResume(userId: Int, bytes: ByteArray): ImportResponse {
        if (bytes.isEmpty()) throw BusinessException("Envie o seu currículo em PDF, DOCX ou TXT")
        checkSize(bytes)
        val text = ResumeTextExtractor.extract(bytes)
        val parsed = ResumeParser.parse(text)
        // Skills citadas nas experiências também entram, além das listadas na seção de competências.
        val mentioned = SkillExtractor(catalog.skillAliases()).extract(text).map { it.skillName }
        val data = parsed.copy(skills = (parsed.skills + mentioned).distinctBy { it.lowercase() })
        if (data.positions.isEmpty() && data.educations.isEmpty() && data.skills.isEmpty() && data.languages.isEmpty()) {
            throw BusinessException(
                "Não conseguimos identificar experiências, formação, skills nem idiomas neste arquivo. " +
                    "Confira se o currículo tem seções como \"Experiência\" e \"Formação\", ou preencha o perfil à mão."
            )
        }
        return savePreview(userId, bytes, data, "RESUME")
    }

    private fun checkSize(bytes: ByteArray) {
        if (bytes.size > MAX_UPLOAD_BYTES) throw BusinessException("O arquivo passa do tamanho máximo de 10 MB")
    }

    private fun savePreview(userId: Int, bytes: ByteArray, data: LinkedInData, source: String): ImportResponse {
        val preview = buildPreview(userId, data)
        val id = jdbc.sql(
            """
            INSERT INTO core.profile_import (users_id, import_source, file_sha256, preview, items_total)
            VALUES (:userId, :source, :sha, CAST(:preview AS jsonb), :total)
            RETURNING profile_import_id
            """
        )
            .param("userId", userId)
            .param("source", source)
            .param("sha", RuleSet.sha256Bytes(bytes))
            .param("preview", mapper.writeValueAsString(preview))
            .param("total", preview.total())
            .query(Long::class.java)
            .single()
        return find(userId, id)
    }

    @Transactional(readOnly = true)
    fun find(userId: Int, id: Long): ImportResponse =
        jdbc.sql(
            """
            SELECT profile_import_id, status, import_source, items_total, items_applied, created_at, expires_at, applied_at,
                   CAST(preview AS text) AS preview
              FROM core.profile_import
             WHERE profile_import_id = :id AND users_id = :userId
               AND (status = 'APPLIED' OR expires_at > now())
            """
        ).param("id", id).param("userId", userId).query { rs, _ ->
            ImportResponse(
                id = rs.getLong("profile_import_id"),
                status = rs.getString("status"),
                importSource = rs.getString("import_source"),
                itemsTotal = rs.getInt("items_total"),
                itemsApplied = rs.getInt("items_applied"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java),
                appliedAt = rs.getObject("applied_at", OffsetDateTime::class.java),
                preview = rs.getString("preview").takeIf { it != "{}" }
                    ?.let { mapper.readValue(it, ImportPreview::class.java) } ?: ImportPreview()
            )
        }.optional().orElseThrow { NotFoundException("Importação não encontrada") }

    @Transactional
    fun apply(userId: Int, id: Long, request: ApplyImportRequest): ImportResponse {
        val current = find(userId, id)
        if (current.status != "PREVIEW") throw ConflictException("Esta importação já foi aplicada ou descartada")
        val chosen = request.refs?.toSet()
        val source = if (current.importSource == "RESUME") DataSource.RESUME_IMPORT else DataSource.LINKEDIN_IMPORT
        fun selected(ref: String, status: ItemStatus) = status == ItemStatus.NEW && (chosen == null || ref in chosen)

        var applied = 0
        current.preview.experiences.filter { selected(it.ref, it.status) }.forEach { item ->
            if (repository.experienceRefExists(userId, item.ref)) return@forEach
            val data = try {
                profileService.experienceData(
                    ExperienceRequest(
                        jobTitle = item.jobTitle,
                        organizationName = item.organizationName,
                        experienceType = if (isInternship(item.jobTitle)) ExperienceType.INTERNSHIP else ExperienceType.EMPLOYMENT,
                        description = item.description,
                        startDate = item.startDate,
                        endDate = item.endDate,
                        current = item.current
                    )
                )
            } catch (ex: BusinessException) {
                return@forEach
            }
            repository.insertExperience(userId, data.copy(source = source, externalRef = item.ref))
            applied++
        }
        current.preview.educations.filter { selected(it.ref, it.status) }.forEach { item ->
            val educationId = item.educationId ?: return@forEach
            val start = item.startDate ?: return@forEach
            if (repository.educationRefExists(userId, item.ref)) return@forEach
            catalog.organizationId(item.institution, "EDUCATIONAL_INSTITUTION")
            repository.insertEducation(
                userId,
                EducationData(educationId, item.institution, start, item.endDate, item.current, source, item.ref)
            )
            applied++
        }
        current.preview.skills.filter { selected(it.ref, it.status) }.forEach { item ->
            val skillId = item.skillId ?: return@forEach
            if (repository.insertSkill(userId, skillId, SkillLevel.INTERMEDIATE, null, source) != null) applied++
        }
        current.preview.languages.filter { selected(it.ref, it.status) }.forEach { item ->
            val languageId = item.languageId ?: return@forEach
            if (repository.insertLanguage(userId, languageId, item.proficiencyLevel, source) != null) applied++
        }
        if (current.importSource == "RESUME") fillPersonalFields(userId, current.preview.profile)

        jdbc.sql(
            """
            UPDATE core.profile_import SET status = 'APPLIED', applied_at = now(), items_applied = :applied
             WHERE profile_import_id = :id AND users_id = :userId
            """
        ).param("applied", applied.coerceAtMost(current.itemsTotal)).param("id", id).param("userId", userId).update()
        if (applied > 0) tracker.changed(userId)
        return find(userId, id)
    }

    @Transactional
    fun discard(userId: Int, id: Long) {
        val updated = jdbc.sql(
            "UPDATE core.profile_import SET status = 'DISCARDED' WHERE profile_import_id = :id AND users_id = :userId AND status = 'PREVIEW'"
        ).param("id", id).param("userId", userId).update()
        if (updated == 0) throw NotFoundException("Importação não encontrada")
    }

    // Objetivo e local do currículo só entram se o perfil ainda não tiver: nunca sobrescrevem o que foi digitado.
    private fun fillPersonalFields(userId: Int, profile: ProfileItem?) {
        if (profile == null) return
        val personal = repository.personal(userId)
        val objective = profile.headline?.takeIf { it.isNotBlank() && personal.objective.isNullOrBlank() }
        val locationId = profile.location?.takeIf { personal.location == null }?.let(::parseLocation)?.let { profileService.locationId(it) }
        if (objective == null && locationId == null) return
        repository.updatePersonal(userId, PersonalRequest(objective = objective), locationId, setLocation = locationId != null)
    }

    // "São Paulo, SP, Brasil" ou "Curitiba, PR"; fora do Brasil o local fica para o usuário preencher.
    private fun parseLocation(text: String): LocationInput? {
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val state = parts.getOrNull(1)?.takeIf { it.matches(Regex("[A-Z]{2}")) }
        val country = parts.lastOrNull()?.lowercase()
        if (parts.isEmpty() || (state == null && country != "brasil" && country != "brazil")) return null
        return LocationInput("BR", state, parts.first())
    }

    // Compara cada item com o perfil atual: novo, já existe ou conflita com o que o usuário digitou.
    private fun buildPreview(userId: Int, data: LinkedInData): ImportPreview {
        val existingExperiences = repository.experiences(userId)
        val existingEducations = repository.educations(userId)
        val userSkillIds = repository.skills(userId).map { it.skillId }.toSet()
        val userLanguages = repository.languages(userId).associateBy { it.languageId }

        val experiences = data.positions.map { p ->
            val ref = ref("EXPERIENCE", TextNormalizer.forMatching(p.title), NameNormalizer.normalize(p.company), p.startDate?.toString())
            val sameCompanyAndStart = existingExperiences.firstOrNull {
                NameNormalizer.normalize(it.organizationName) == NameNormalizer.normalize(p.company) &&
                    p.startDate != null && it.startDate.withDayOfMonth(1) == p.startDate.withDayOfMonth(1)
            }
            val status = when {
                p.startDate == null -> ItemStatus.INVALID
                repository.experienceRefExists(userId, ref) -> ItemStatus.EXISTS
                sameCompanyAndStart != null &&
                    TextNormalizer.forMatching(sameCompanyAndStart.jobTitle) == TextNormalizer.forMatching(p.title) -> ItemStatus.EXISTS
                sameCompanyAndStart != null -> ItemStatus.CONFLICT
                else -> ItemStatus.NEW
            }
            ExperienceItem(ref, status, p.title, p.company, p.location, p.description, p.startDate, p.endDate, p.endDate == null)
        }

        val educations = data.educations.map { e ->
            val ref = ref("EDUCATION", NameNormalizer.normalize(e.school), TextNormalizer.forMatching(e.degree), e.startDate?.toString())
            val (educationId, level, field) = educationCatalog(e.degree, e.notes)
            val sameSchool = existingEducations.firstOrNull {
                NameNormalizer.normalize(it.institution) == NameNormalizer.normalize(e.school) &&
                    e.startDate != null && it.startDate.year == e.startDate.year
            }
            val status = when {
                e.startDate == null || educationId == null -> ItemStatus.INVALID
                repository.educationRefExists(userId, ref) -> ItemStatus.EXISTS
                sameSchool != null && sameSchool.educationId == educationId -> ItemStatus.EXISTS
                sameSchool != null -> ItemStatus.CONFLICT
                else -> ItemStatus.NEW
            }
            EducationItem(ref, status, e.school, e.degree, educationId, level, field, e.startDate, e.endDate,
                e.endDate == null || e.endDate.isAfter(LocalDate.now()))
        }

        val skills = data.skills.map { name ->
            val normalized = TextNormalizer.forSkills(name)
            val skillId = skillId(normalized)
            val status = when {
                skillId == null -> ItemStatus.UNRECOGNIZED
                skillId in userSkillIds -> ItemStatus.EXISTS
                else -> ItemStatus.NEW
            }
            SkillItem(ref("SKILL", normalized), status, name, skillId)
        }.distinctBy { it.skillId ?: it.ref }

        val languages = data.languages.map { l ->
            val languageId = languageId(l.name)
            val level = l.level ?: ProficiencyLevel.B1
            val existing = languageId?.let { userLanguages[it] }
            val status = when {
                languageId == null -> ItemStatus.UNRECOGNIZED
                existing == null -> ItemStatus.NEW
                existing.proficiencyLevel == level -> ItemStatus.EXISTS
                else -> ItemStatus.CONFLICT
            }
            LanguageItem(ref("LANGUAGE", TextNormalizer.forMatching(l.name)), status, l.name, languageId, level)
        }

        return ImportPreview(experiences, educations, skills, languages, data.profile?.let { ProfileItem(it.headline, it.location) })
    }

    private fun skillId(normalized: String): Long? =
        jdbc.sql(
            """
            SELECT s.skill_id FROM core.skill s
             WHERE lower(s.skill_name) = :name
                OR EXISTS (SELECT 1 FROM core.skill_alias a WHERE a.skill_id = s.skill_id AND a.normalized_alias = :name)
             LIMIT 1
            """
        ).param("name", normalized).query(Long::class.java).optional().orElse(null)

    private fun languageId(name: String): Long? {
        val normalized = TextNormalizer.forMatching(name)
        val target = LANGUAGE_NAMES[normalized] ?: normalized
        return jdbc.sql("SELECT language_id, language_name FROM core.\"language\"")
            .query { rs, _ -> rs.getLong("language_id") to TextNormalizer.forMatching(rs.getString("language_name")) }
            .list()
            .firstOrNull { it.second == target }
            ?.first
    }

    // Nível pelo nome do diploma; área pelo catálogo (ou "Outra área" do mesmo nível).
    private fun educationCatalog(degree: String?, notes: String?): Triple<Long?, String?, String?> {
        val text = TextNormalizer.forMatching(listOfNotNull(degree, notes).joinToString(" "))
        val level = educationLevel(text)
        val rows = jdbc.sql("SELECT education_id, field_of_study FROM core.education WHERE education_level = :level")
            .param("level", level)
            .query { rs, _ -> rs.getLong("education_id") to rs.getString("field_of_study") }
            .list()
        val match = rows.filter { (_, field) -> field != "Outra área" && TextNormalizer.forMatching(field) in text }
            .maxByOrNull { it.second.length }
            ?: rows.firstOrNull { it.second == "Outra área" }
            ?: rows.firstOrNull()
        return Triple(match?.first, level, match?.second)
    }

    private fun isInternship(title: String): Boolean {
        val text = TextNormalizer.forMatching(title)
        return listOf("estagio", "estagiario", "intern", "internship").any { " $it " in " $text " }
    }

    private fun ref(vararg parts: String?): String = RuleSet.sha256(parts.joinToString("|") { it.orEmpty() })

    companion object {
        const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024

        private val LANGUAGE_NAMES = mapOf(
            "english" to "ingles", "portuguese" to "portugues", "spanish" to "espanhol", "french" to "frances",
            "german" to "alemao", "italian" to "italiano", "japanese" to "japones", "chinese" to "mandarim",
            "mandarin" to "mandarim", "korean" to "coreano", "russian" to "russo", "arabic" to "arabe", "dutch" to "holandes"
        )

        fun educationLevel(text: String): String = when {
            "mba" in text.split(' ') -> "MBA"
            listOf("doutorado", "phd", "doctor", "doctorate").any { it in text } -> "DOCTORATE"
            listOf("mestrado", "master", "msc").any { it in text } -> "MASTER"
            listOf("pos graduacao", "especializacao", "postgraduate", "pos-graduacao", "lato sensu").any { it in text } -> "POSTGRADUATE"
            listOf("tecnologo", "tecnologia em", "tecnologo em").any { it in text } -> "TECHNOLOGIST"
            listOf("tecnico", "technical").any { it in text } -> "TECHNICAL"
            listOf("ensino medio", "high school").any { it in text } -> "HIGH_SCHOOL"
            else -> "BACHELOR"
        }
    }
}
