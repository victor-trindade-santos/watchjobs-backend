package com.watchjobs.service

import com.watchjobs.entity.PasswordResetCode
import com.watchjobs.exception.BusinessException
import com.watchjobs.mail.Mailer
import com.watchjobs.repository.PasswordResetCodeRepository
import com.watchjobs.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Service
class PasswordResetService(
    private val userRepository: UserRepository,
    private val codes: PasswordResetCodeRepository,
    private val passwordEncoder: PasswordEncoder,
    private val refreshTokenService: RefreshTokenService,
    private val mailer: Mailer
) {
    private val random = SecureRandom()

    @Transactional
    fun forgot(email: String) {
        val user = userRepository.findByEmailIgnoreCase(email.trim().lowercase()) ?: return
        val usersId = requireNotNull(user.id)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        if (codes.countByUser_IdAndCreatedAtGreaterThanEqual(usersId, now.minusHours(1)) >= 3) return

        codes.revokeActiveByUsersId(usersId, now)
        val code = "%06d".format(random.nextInt(1_000_000))
        codes.save(
            PasswordResetCode(
                user = user,
                codeHash = requireNotNull(passwordEncoder.encode(code)),
                createdAt = now,
                expiresAt = now.plusMinutes(15)
            )
        )
        mailer.send(
            user.email,
            "Código para redefinir sua senha",
            """
            Seu código para redefinir a senha do WatchJobs é $code.

            Ele vale por 15 minutos. Se você não pediu essa redefinição, ignore este e-mail.
            """.trimIndent()
        )
    }

    // Sem rollback: a tentativa errada precisa ficar gravada mesmo com o 400.
    @Transactional(noRollbackFor = [BusinessException::class])
    fun reset(email: String, code: String, newPassword: String, confirmPassword: String) {
        if (newPassword != confirmPassword) {
            throw BusinessException("A senha e a confirmação de senha não coincidem")
        }
        if (newPassword.length !in 8..255 || !PASSWORD.matches(newPassword)) {
            throw BusinessException("Senha deve ter entre 8 e 255 caracteres, com pelo menos uma letra e um número")
        }
        val user = userRepository.findByEmailIgnoreCase(email.trim().lowercase())
            ?: invalidCode()
        val usersId = requireNotNull(user.id)
        val stored = codes.findFirstByUser_IdAndUsedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDescIdDesc(usersId)
            ?: invalidCode()
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        if (stored.attempts >= MAX_ATTEMPTS || !stored.expiresAt.isAfter(now)) invalidCode()
        if (!passwordEncoder.matches(code, stored.codeHash)) {
            stored.attempts = (stored.attempts + 1).toShort()
            invalidCode()
        }
        user.password = requireNotNull(passwordEncoder.encode(newPassword))
        user.passwordChangedAt = now
        user.updatedAt = now
        stored.usedAt = now
        refreshTokenService.revokeAll(usersId)
        userRepository.save(user)
    }

    private fun invalidCode(): Nothing =
        throw BusinessException("Código inválido ou expirado")

    private companion object {
        const val MAX_ATTEMPTS: Short = 5
        val PASSWORD = Regex("^(?=.*[A-Za-z])(?=.*\\d).+$")
    }
}
