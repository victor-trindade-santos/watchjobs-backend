package com.watchjobs.controller

import com.watchjobs.dto.request.SigninRequest
import com.watchjobs.dto.request.SignupRequest
import com.watchjobs.dto.request.RefreshRequest
import com.watchjobs.dto.response.AuthResponse
import com.watchjobs.dto.response.UserResponse
import com.watchjobs.service.RefreshTokenService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(properties = ["spring.docker.compose.enabled=false"])
@AutoConfigureMockMvc
abstract class AuthSessionPostgresTestSupport {
    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    @Autowired
    protected lateinit var refreshTokenService: RefreshTokenService

    private val createdUserIds = mutableListOf<Int>()

    @BeforeEach
    fun requirePostgres() {
        requireNotNull(jdbc.dataSource).connection.use { connection ->
            assertEquals("PostgreSQL", connection.metaData.databaseProductName)
        }
    }

    @AfterEach
    fun cleanupUsers() {
        createdUserIds.forEach { usersId ->
            jdbc.update("delete from core.users where users_id = ?", usersId)
        }
        createdUserIds.clear()
    }

    protected fun signupAndLogin(): AuthResponse {
        val email = "session-${UUID.randomUUID()}@example.com"
        val request = SignupRequest(
            firstName = "Session",
            lastName = "Test",
            birthDate = java.time.LocalDate.of(1995, 1, 1),
            email = email,
            password = "teste1234",
            confirmPassword = "teste1234"
        )
        val signup = mockMvc.post("/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect { status { isCreated() } }.andReturn()
        val user = objectMapper.readValue(signup.response.contentAsString, UserResponse::class.java)
        createdUserIds.add(user.id)
        val signin = mockMvc.post("/signin") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(SigninRequest(email, "teste1234"))
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readValue(signin.response.contentAsString, AuthResponse::class.java)
    }

    protected fun refresh(value: String) = mockMvc.post("/auth/refresh") {
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(RefreshRequest(value))
    }

    protected fun tokenId(value: String): Long = requireNotNull(
        jdbc.queryForObject(
            "select refresh_token_id from core.refresh_token where token_hash = ?",
            Long::class.java,
            refreshTokenService.hash(value)
        )
    )
}

class AuthRefreshPostgresTest : AuthSessionPostgresTestSupport() {
    @Test
    fun `login persists only hash and refresh rotates the session`() {
        val login = signupAndLogin()
        assertEquals(32, Base64.getUrlDecoder().decode(login.refreshToken).size)
        assertTrue(login.refreshToken.matches(Regex("[A-Za-z0-9_-]{43}")))
        val claims = objectMapper.readTree(Base64.getUrlDecoder().decode(login.token.split('.')[1]))
        assertEquals(900L, claims["exp"].asLong() - claims["iat"].asLong())
        val oldId = tokenId(login.refreshToken)
        assertEquals(2_592_000L, jdbc.queryForObject(
            "select extract(epoch from (expires_at - created_at))::bigint from core.refresh_token where refresh_token_id = ?",
            Long::class.java, oldId
        ))
        val oldRow = jdbc.queryForMap("select * from core.refresh_token where refresh_token_id = ?", oldId)
        assertEquals(refreshTokenService.hash(login.refreshToken), oldRow["token_hash"].toString().trim())
        assertTrue(oldRow["token_hash"].toString().trim().matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(login.refreshToken, oldRow["token_hash"])

        val result = refresh(login.refreshToken).andExpect { status { isOk() } }.andReturn()
        val renewed = objectMapper.readValue(result.response.contentAsString, AuthResponse::class.java)
        assertNotEquals(login.refreshToken, renewed.refreshToken)
        assertEquals(login.user.id, renewed.user.id)
        val replacementId = tokenId(renewed.refreshToken)
        val updated = jdbc.queryForMap("select * from core.refresh_token where refresh_token_id = ?", oldId)
        assertNotNull(updated["revoked_at"])
        assertNotNull(updated["last_used_at"])
        assertEquals(replacementId, (updated["replaced_by_id"] as Number).toLong())
        val replacement = jdbc.queryForMap("select * from core.refresh_token where refresh_token_id = ?", replacementId)
        assertEquals(oldRow["family_id"], replacement["family_id"])
        assertNull(replacement["revoked_at"])
        refresh(login.refreshToken).andExpect { status { isUnauthorized() } }
        mockMvc.get("/me") {
            header("Authorization", "Bearer ${renewed.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(login.user.id) }
        }
    }

    @Test
    fun `expired and unknown refresh tokens are rejected`() {
        val login = signupAndLogin()
        val oldId = tokenId(login.refreshToken)
        jdbc.update(
            "update core.refresh_token set created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour' where refresh_token_id = ?",
            oldId
        )
        refresh(login.refreshToken).andExpect {
            status { isUnauthorized() }
            jsonPath("$.message") { value("Refresh token inválido ou expirado") }
        }
        refresh("unknown-token").andExpect { status { isUnauthorized() } }
        assertEquals(1L, jdbc.queryForObject(
            "select count(*) from core.refresh_token where users_id = ?", Long::class.java, login.user.id
        ))
    }

    @Test
    fun `reusing a revoked refresh token revokes the whole family`() {
        val login = signupAndLogin()
        val renewed = objectMapper.readValue(
            refresh(login.refreshToken).andExpect { status { isOk() } }.andReturn().response.contentAsString,
            AuthResponse::class.java
        )
        val latest = objectMapper.readValue(
            refresh(renewed.refreshToken).andExpect { status { isOk() } }.andReturn().response.contentAsString,
            AuthResponse::class.java
        )

        refresh(login.refreshToken).andExpect { status { isUnauthorized() } }

        refresh(latest.refreshToken).andExpect { status { isUnauthorized() } }
        assertEquals(0L, jdbc.queryForObject(
            "select count(*) from core.refresh_token where users_id = ? and revoked_at is null",
            Long::class.java, login.user.id
        ))
        assertEquals(3L, jdbc.queryForObject(
            "select count(*) from core.refresh_token where users_id = ?", Long::class.java, login.user.id
        ))
    }

    @Test
    fun `concurrent refresh accepts the old token only once`() {
        val login = signupAndLogin()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests = (1..2).map {
                executor.submit<Int> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    refresh(login.refreshToken).andReturn().response.status
                }
            }
            start.countDown()
            val statuses = requests.map { request -> request.get(20, TimeUnit.SECONDS) }.sorted()
            assertEquals(listOf(200, 401), statuses)
            assertEquals(2L, jdbc.queryForObject(
                "select count(*) from core.refresh_token where users_id = ?", Long::class.java, login.user.id
            ))
            // O pedido que perdeu a corrida usou um refresh já revogado: a família inteira cai (F1-03).
            assertEquals(0L, jdbc.queryForObject(
                "select count(*) from core.refresh_token where users_id = ? and revoked_at is null",
                Long::class.java, login.user.id
            ))
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}