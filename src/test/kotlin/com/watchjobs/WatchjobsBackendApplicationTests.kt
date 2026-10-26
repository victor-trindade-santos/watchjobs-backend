package com.watchjobs

import com.watchjobs.entity.PasswordResetCode
import com.watchjobs.entity.User
import com.watchjobs.repository.PasswordResetCodeRepository
import com.watchjobs.repository.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest(properties = ["spring.docker.compose.enabled=false"])
class WatchjobsBackendApplicationTests {

	@Autowired
	lateinit var jdbc: JdbcTemplate

	@Autowired
	lateinit var users: UserRepository

	@Autowired
	lateinit var codes: PasswordResetCodeRepository

	@Autowired
	lateinit var transactions: TransactionTemplate

	@Test
	fun contextLoads() {
		requireNotNull(jdbc.dataSource).connection.use { connection ->
			assertEquals("PostgreSQL", connection.metaData.databaseProductName)
		}
		assertEquals(1L, jdbc.queryForObject(
			"select count(*) from public.flyway_schema_history where version = '4' and success = true",
			Long::class.java
		))
	}

	@Test
	fun `revoked reset codes still count toward the hourly limit`() {
		transactions.executeWithoutResult { transaction ->
			val now = OffsetDateTime.now(ZoneOffset.UTC)
			val user = users.save(User(
				firstName = "Reset",
				lastName = "Test",
				birthDate = java.time.LocalDate.of(1995, 1, 1),
				email = "reset-${UUID.randomUUID()}@example.com",
				password = "test-hash"
			))
			val usersId = requireNotNull(user.id)
			repeat(3) {
				codes.save(PasswordResetCode(user = user, codeHash = "test-code-hash", createdAt = now, expiresAt = now.plusMinutes(15)))
			}
			codes.flush()
			assertEquals(3L, codes.countByUser_IdAndCreatedAtGreaterThanEqual(usersId, now.minusHours(1)))
			assertEquals(3, codes.revokeActiveByUsersId(usersId, now))
			assertEquals(3L, codes.countByUser_IdAndCreatedAtGreaterThanEqual(usersId, now.minusHours(1)))
			assertNotNull(codes.findFirstByUser_IdOrderByCreatedAtDescIdDesc(usersId))
			transaction.setRollbackOnly()
		}
	}

}
