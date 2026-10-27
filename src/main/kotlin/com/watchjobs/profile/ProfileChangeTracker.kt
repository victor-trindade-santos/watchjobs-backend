package com.watchjobs.profile

import com.watchjobs.profile.inference.ProfileSummaryService
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

// Ponto único chamado por toda escrita no perfil: sobe o profile_version e recalcula o perfil
// deduzido na mesma transação (as notas antigas deixam de valer).
@Component
class ProfileChangeTracker(
    private val versions: ProfileVersions,
    private val summaries: ProfileSummaryService
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun changed(userId: Int): Int {
        val version = versions.bump(userId)
        summaries.recompute(userId, version)
        return version
    }
}
