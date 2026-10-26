package com.watchjobs.ingestion

import com.watchjobs.classification.ClassificationInput
import com.watchjobs.classification.ClassificationRuleLoader
import com.watchjobs.classification.RuleBasedJobClassifier
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.assertEquals

// F2-17: as regras do R__seed_rules.sql, lidas do banco, classificam títulos reais em português e inglês.
class ClassificationRulesPostgresTest : DiscoveryPostgresTestSupport() {
    @Autowired
    lateinit var loader: ClassificationRuleLoader

    @Autowired
    lateinit var classifier: RuleBasedJobClassifier

    @Test
    fun `every role family rule points to an existing area`() {
        assertEquals(0L, jdbc.queryForObject(
            """
            SELECT count(*) FROM core.classification_rule r
             WHERE r.rule_type = 'ROLE_FAMILY'
               AND NOT EXISTS (SELECT 1 FROM core.role_family f WHERE f.code = r.target_value)
            """,
            Long::class.java
        ))
        assertEquals(12, loader.reload().version.length)
    }

    @Test
    fun `real titles are classified by area and seniority`() {
        loader.reload()
        // título -> (área, nível); null quando as regras não devem decidir.
        val cases = listOf(
            "Senior Backend Engineer" to ("BACKEND" to "SENIOR"),
            "Desenvolvedor Back-end Pleno" to ("BACKEND" to "MID"),
            "Desenvolvedora Java Júnior" to ("BACKEND" to "JUNIOR"),
            "Software Engineer II, Backend" to ("BACKEND" to "MID"),
            "Staff Software Engineer - Payments" to (null to "SPECIALIST"),
            "Front-end Developer (React)" to ("FRONTEND" to null),
            "Desenvolvedor(a) Frontend Sênior" to ("FRONTEND" to "SENIOR"),
            "Full Stack Engineer (React/Node)" to ("FULLSTACK" to null),
            "Desenvolvedor Fullstack Pleno" to ("FULLSTACK" to "MID"),
            "iOS Engineer" to ("MOBILE" to null),
            "Desenvolvedor Android Sr" to ("MOBILE" to "SENIOR"),
            "Mobile Developer (Flutter)" to ("MOBILE" to null),
            "React Native Developer" to ("MOBILE" to null),
            "Data Engineer" to ("DATA_ENGINEERING" to null),
            "Engenheiro(a) de Dados Sênior" to ("DATA_ENGINEERING" to "SENIOR"),
            "Analytics Engineer" to ("DATA_ENGINEERING" to null),
            "Data Scientist II" to ("DATA_SCIENCE" to "MID"),
            "Cientista de Dados Pleno" to ("DATA_SCIENCE" to "MID"),
            "Machine Learning Engineer" to ("DATA_SCIENCE" to null),
            "Analista de Dados Júnior" to ("DATA_ANALYTICS" to "JUNIOR"),
            "Business Intelligence Analyst" to ("DATA_ANALYTICS" to null),
            "Site Reliability Engineer" to ("DEVOPS_SRE" to null),
            "DevOps Engineer Sr." to ("DEVOPS_SRE" to "SENIOR"),
            "Engenheiro de Plataforma" to ("DEVOPS_SRE" to null),
            "Cloud Engineer (AWS)" to ("DEVOPS_SRE" to null),
            "Security Engineer" to ("SECURITY" to null),
            "Analista de Segurança da Informação Pleno" to ("SECURITY" to "MID"),
            "QA Engineer" to ("QA" to null),
            "Analista de Testes Júnior" to ("QA" to "JUNIOR"),
            "SDET - Test Automation" to ("QA" to null),
            "Product Designer" to ("DESIGN" to null),
            "UX/UI Designer Sênior" to ("DESIGN" to "SENIOR"),
            "Product Manager" to ("PRODUCT" to null),
            "Product Owner Sênior" to ("PRODUCT" to "SENIOR"),
            "Gerente de Produto" to ("PRODUCT" to null),
            "Scrum Master" to ("PROJECT_AGILE" to null),
            "Gerente de Projetos de TI" to ("PROJECT_AGILE" to null),
            "Analista de Suporte Técnico N1" to ("IT_SUPPORT" to null),
            "Help Desk Analyst" to ("IT_SUPPORT" to null),
            "Tech Lead Java" to ("BACKEND" to "LEAD"),
            "Estágio em Desenvolvimento de Software" to (null to "INTERN"),
            "Trainee de Tecnologia" to (null to "TRAINEE"),
            "Engineering Manager, Platform" to (null to "LEAD"),
            "Principal Engineer" to (null to "SPECIALIST"),
            "Desenvolvedor .NET Pleno" to ("BACKEND" to "MID"),
            "Python Developer" to ("BACKEND" to null),
            "Golang Engineer (Remote)" to ("BACKEND" to null),
            "Analista de Sistemas Pl" to (null to "MID"),
            "Engenheiro(a) de Software III" to (null to "SENIOR")
        )
        assertAll(cases.map { (title, expected) ->
            {
                val result = classifier.classify(ClassificationInput(title))
                assertEquals(expected, result.roleFamily?.value to result.seniority?.value, title)
            }
        })
    }

    @Test
    fun `work mode and contract type come from title and location`() {
        loader.reload()
        val remote = classifier.classify(ClassificationInput("Golang Engineer (Remote)"))
        assertEquals("REMOTE", remote.workMode?.value)
        val hybrid = classifier.classify(ClassificationInput("Backend Developer", location = "Híbrido - São Paulo"))
        assertEquals("HYBRID", hybrid.workMode?.value)
        val internship = classifier.classify(ClassificationInput("Estágio em Dados"))
        assertEquals("INTERNSHIP", internship.jobType?.value)
        val pj = classifier.classify(ClassificationInput("Desenvolvedor Java", description = "Contratação PJ, 100% remoto"))
        assertEquals("PJ", pj.jobType?.value)
        assertEquals(0.3, pj.jobType?.confidence)
    }
}
