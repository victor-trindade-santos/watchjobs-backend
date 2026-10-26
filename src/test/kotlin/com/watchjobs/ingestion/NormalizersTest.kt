package com.watchjobs.ingestion

import com.watchjobs.catalog.NameNormalizer
import com.watchjobs.catalog.TextNormalizer
import com.watchjobs.ingestion.normalize.HtmlText
import com.watchjobs.ingestion.normalize.LocationNormalizer
import com.watchjobs.ingestion.normalize.NormalizedLocation
import com.watchjobs.ingestion.normalize.SalaryNormalizer
import com.watchjobs.ingestion.normalize.SkillExtractor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Regras puras de normalização, em tabela (F2-09).
class NormalizersTest {
    private val extractor = SkillExtractor(IngestionFixtures.aliases)

    @Test
    fun `locations from free text`() {
        val cases = listOf(
            "São Paulo, Brazil" to NormalizedLocation("São Paulo", "SP", "BR", false),
            "Remote - Brazil" to NormalizedLocation(null, null, "BR", true),
            "Rio de Janeiro - RJ" to NormalizedLocation("Rio de Janeiro", "RJ", "BR", false),
            "São Paulo, SP, Brasil" to NormalizedLocation("São Paulo", "SP", "BR", false),
            "Remote" to NormalizedLocation(null, null, null, true),
            "Lisbon, Portugal" to NormalizedLocation("Lisbon", null, "PT", false),
            "Remote (LATAM)" to NormalizedLocation(null, null, null, true),
            "Florianópolis" to NormalizedLocation("Florianópolis", "SC", "BR", false),
            "São Paulo, São Paulo" to NormalizedLocation("São Paulo", "SP", "BR", false),
            "Hybrid - Belo Horizonte, MG" to NormalizedLocation("Belo Horizonte", "MG", "BR", false),
            "Brazil" to NormalizedLocation(null, null, "BR", false),
            "Remote, US" to NormalizedLocation(null, null, "US", true),
            "Remoto - Curitiba" to NormalizedLocation("Curitiba", "PR", "BR", true)
        )
        assertAll(cases.map { (raw, expected) -> { assertEquals(expected, LocationNormalizer.normalize(raw), raw) } })
        assertEquals(NormalizedLocation(), LocationNormalizer.normalize(null))
        assertEquals("BR", LocationNormalizer.normalize("Remote", defaultCountry = "BR").countryCode)
    }

    @Test
    fun `salary only when the source sends it`() {
        assertNull(SalaryNormalizer.normalize(null, null, "BRL", null))
        assertNull(SalaryNormalizer.normalize(0.0, -10.0, "BRL", null))
        val swapped = SalaryNormalizer.normalize(12000.0, 9000.0, "brl", "month")!!
        assertEquals(9000.0, swapped.min)
        assertEquals(12000.0, swapped.max)
        assertEquals("BRL", swapped.currency)
        assertEquals("MONTH", swapped.period)
        assertNull(SalaryNormalizer.normalize(5000.0, null, "R$", "per month")!!.currency)
    }

    @Test
    fun `html becomes clean text and snippets are short`() {
        assertEquals("Java & SQL", HtmlText.toText("&lt;p&gt;Java &amp;amp; SQL&lt;/p&gt;"))
        assertEquals("Olá mundo", HtmlText.toText("<div><p>Olá</p>\n<p>mundo</p></div>"))
        assertNull(HtmlText.toText("   "))
        val long = "palavra ".repeat(200)
        val snippet = HtmlText.snippet(long)!!
        assertTrue(snippet.length <= 501)
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `text normalization for rules`() {
        assertAll(
            { assertEquals("desenvolvedor back end pleno", TextNormalizer.forMatching("Desenvolvedor(a) Back-end Pleno")) },
            { assertEquals("engenheiro de dados senior", TextNormalizer.forMatching("Engenheira de Dados Sênior")) },
            { assertEquals("desenvolvedor net sr", TextNormalizer.forMatching("Desenvolvedor/a .NET Sr.")) },
            { assertEquals("", TextNormalizer.forMatching(null)) }
        )
    }

    @Test
    fun `company names normalize to the same key`() {
        assertAll(
            { assertEquals("nubank", NameNormalizer.normalize("Nubank")) },
            { assertEquals("nubank", NameNormalizer.normalize("NUBANK ")) },
            { assertEquals("banco inter", NameNormalizer.normalize("Banco Inter S.A.")) },
            { assertEquals("stone pagamentos", NameNormalizer.normalize("Stone Pagamentos S/A")) },
            { assertEquals("acme tecnologia", NameNormalizer.normalize("Acme Tecnologia Ltda.")) },
            { assertEquals("sao joao", NameNormalizer.normalize("São  João")) }
        )
    }

    @Test
    fun `skills from aliases`() {
        fun names(text: String) = extractor.extract(text).map { it.skillName }

        assertAll(
            { assertEquals(listOf("Java", "Spring Boot", "PostgreSQL"), names("Java, Spring Boot e Postgres")) },
            { assertEquals(listOf("Node.js"), names("NodeJS ou Node.js")) },
            { assertEquals(listOf("Go", "Kotlin", "Java"), names("Experience with Go, Kotlin and Java")) },
            { assertEquals(listOf("Go"), names("Golang developer")) },
            { assertEquals(listOf("Go"), names("Desenvolvedor Go sênior")) },
            { assertEquals(emptyList(), names("Let's go to the office. Go to market strategy.")) },
            { assertEquals(listOf("Python", "R", "SQL"), names("Python, R and SQL")) },
            { assertEquals(emptyList(), names("R&D department")) },
            { assertEquals(listOf("C#"), names("C# developer")) },
            { assertEquals(listOf("C++"), names("Modern C++ codebase")) },
            { assertEquals(emptyList(), names("Ruby on the side")) },
            { assertEquals(listOf("Java", "Kotlin"), names("Requisitos: Java. Diferencial: Kotlin")) }
        )
        val required = extractor.extract("Requisitos: Java. Diferencial: Kotlin").associate { it.skillName to it.required }
        assertEquals(mapOf("Java" to true, "Kotlin" to false), required)
    }
}
