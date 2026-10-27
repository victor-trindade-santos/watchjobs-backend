package com.watchjobs.profile

import com.watchjobs.matching.FeedFilter
import com.watchjobs.matching.FeedService
import com.watchjobs.matching.MatchingEngine
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

data class ProfileStatsResponse(
    val completeness: Int,
    val missing: List<String>,
    val skillCount: Int,
    val compatibleJobs: Long,
    val compatibleMinScore: Int,
    val matchPrecision: Int,
    val linkedin: LinkedInStatus
)

// Importação do LinkedIn: só há registro depois de aplicada (o arquivo nunca é guardado).
data class LinkedInStatus(
    val imported: Boolean,
    val lastImportAt: OffsetDateTime?,
    val importedExperiences: Int,
    val importedSkills: Int
)

// Números do cartão "Estatísticas" do perfil.
@Service
class ProfileStatsService(
    private val profiles: ProfileService,
    private val feed: FeedService,
    private val jdbc: JdbcClient
) {
    @Transactional
    fun stats(userId: Int): ProfileStatsResponse {
        val profile = profiles.profile(userId)
        val (completeness, missing) = completeness(profile)
        val compatible = feed.feed(userId, FeedFilter(minScore = COMPATIBLE_MIN_SCORE.toDouble(), size = 1)).total
        val lastImport = jdbc.sql("SELECT max(applied_at) FROM core.profile_import WHERE users_id = :userId AND status = 'APPLIED'")
            .param("userId", userId).query(OffsetDateTime::class.java).optional().orElse(null)
        return ProfileStatsResponse(
            completeness = completeness,
            missing = missing,
            skillCount = profile.skills.size,
            compatibleJobs = compatible,
            compatibleMinScore = COMPATIBLE_MIN_SCORE,
            matchPrecision = precision(profile),
            linkedin = LinkedInStatus(
                imported = lastImport != null,
                lastImportAt = lastImport,
                importedExperiences = profile.experiences.count { it.source == DataSource.LINKEDIN_IMPORT },
                importedSkills = profile.skills.count { it.source == DataSource.LINKEDIN_IMPORT }
            )
        )
    }

    private fun completeness(profile: ProfileResponse): Pair<Int, List<String>> {
        val missing = mutableListOf<String>()
        var total = 0
        fun add(weight: Int, done: Boolean, label: String) {
            if (done) total += weight else missing += label
        }
        add(10, profile.personal.location != null, "Informar a localização")
        add(10, !profile.personal.objective.isNullOrBlank(), "Escrever o objetivo profissional")
        add(20, profile.experiences.isNotEmpty(), "Adicionar uma experiência")
        add(15, profile.educations.isNotEmpty(), "Adicionar uma formação")
        val skillPoints = (profile.skills.size.coerceAtMost(SKILLS_FOR_FULL_SCORE) * 20) / SKILLS_FOR_FULL_SCORE
        total += skillPoints
        if (skillPoints < 20) missing += "Adicionar skills (ideal: $SKILLS_FOR_FULL_SCORE ou mais)"
        add(10, profile.languages.isNotEmpty(), "Adicionar um idioma")
        add(15, profile.preferences?.confirmed == true, "Confirmar as preferências")
        return total to missing
    }

    // Parte do peso dos critérios do match que o perfil consegue avaliar.
    private fun precision(profile: ProfileResponse): Int {
        val prefs = profile.preferences
        val summary = profile.summary
        var weight = 0
        if (!prefs?.preferredRoles.isNullOrEmpty() || summary?.primaryRoleFamily != null) weight += MatchingEngine.ROLE
        if (profile.skills.isNotEmpty()) weight += MatchingEngine.REQUIRED_SKILLS + MatchingEngine.OPTIONAL_SKILLS
        if (prefs?.preferredExperienceLevel != null || summary?.experienceLevel != null) weight += MatchingEngine.SENIORITY
        if (prefs != null && prefs.preferredWorkMode != PreferredWorkMode.ANY) weight += MatchingEngine.WORK_MODE
        if (prefs?.salaryMin != null) weight += MatchingEngine.SALARY
        if (prefs?.preferredJobType != null) weight += MatchingEngine.CONTRACT
        return weight
    }

    private companion object {
        const val COMPATIBLE_MIN_SCORE = 70
        const val SKILLS_FOR_FULL_SCORE = 5
    }
}
