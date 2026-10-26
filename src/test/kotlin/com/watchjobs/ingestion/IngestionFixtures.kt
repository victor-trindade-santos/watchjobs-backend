package com.watchjobs.ingestion

import com.watchjobs.classification.ClassificationInput
import com.watchjobs.classification.ClassificationRule
import com.watchjobs.classification.JobClassification
import com.watchjobs.classification.JobClassifier
import com.watchjobs.classification.MatchType
import com.watchjobs.classification.RuleBasedJobClassifier
import com.watchjobs.classification.RuleSet
import com.watchjobs.classification.RuleTarget
import com.watchjobs.classification.RuleType
import com.watchjobs.ingestion.normalize.MatchPolicy
import com.watchjobs.ingestion.normalize.SkillAliasEntry
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

// Peças em memória para testar conectores, normalizadores e o pipeline sem internet.
object IngestionFixtures {
    val mapper: ObjectMapper = jacksonObjectMapper()

    fun read(path: String): String =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$path")) { "Fixture não encontrada: $path" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    fun rule(type: RuleType, pattern: String, target: String, priority: Int) = ClassificationRule(
        type = type,
        pattern = pattern,
        matchType = if (pattern.contains(' ')) MatchType.PHRASE else MatchType.WORD,
        targetValue = target,
        priority = priority
    )

    val ruleSet = RuleSet(
        listOf(
            rule(RuleType.ROLE_FAMILY, "fullstack", "FULLSTACK", 10),
            rule(RuleType.ROLE_FAMILY, "full stack", "FULLSTACK", 10),
            rule(RuleType.ROLE_FAMILY, "mobile", "MOBILE", 10),
            rule(RuleType.ROLE_FAMILY, "flutter", "MOBILE", 10),
            rule(RuleType.ROLE_FAMILY, "data engineer", "DATA_ENGINEERING", 15),
            rule(RuleType.ROLE_FAMILY, "engenheiro de dados", "DATA_ENGINEERING", 15),
            rule(RuleType.ROLE_FAMILY, "frontend", "FRONTEND", 20),
            rule(RuleType.ROLE_FAMILY, "front end", "FRONTEND", 20),
            rule(RuleType.ROLE_FAMILY, "backend", "BACKEND", 30),
            rule(RuleType.ROLE_FAMILY, "back end", "BACKEND", 30),
            rule(RuleType.ROLE_FAMILY, "java", "BACKEND", 35),
            rule(RuleType.SENIORITY, "lead", "LEAD", 10),
            rule(RuleType.SENIORITY, "estagio", "INTERN", 14),
            rule(RuleType.SENIORITY, "senior", "SENIOR", 20),
            rule(RuleType.SENIORITY, "sr", "SENIOR", 20),
            rule(RuleType.SENIORITY, "pleno", "MID", 20),
            rule(RuleType.SENIORITY, "junior", "JUNIOR", 20),
            rule(RuleType.WORK_MODE, "hybrid", "HYBRID", 10),
            rule(RuleType.WORK_MODE, "hibrido", "HYBRID", 10),
            rule(RuleType.WORK_MODE, "remote", "REMOTE", 20),
            rule(RuleType.WORK_MODE, "remoto", "REMOTE", 20),
            rule(RuleType.WORK_MODE, "remota", "REMOTE", 20),
            rule(RuleType.WORK_MODE, "home office", "REMOTE", 20),
            rule(RuleType.JOB_TYPE, "estagio", "INTERNSHIP", 10),
            rule(RuleType.JOB_TYPE, "clt", "CLT", 20),
            rule(RuleType.JOB_TYPE, "pj", "PJ", 20)
        ),
        roleFamilies = setOf("BACKEND", "FRONTEND", "FULLSTACK", "MOBILE", "DATA_ENGINEERING")
    )

    val classifier = object : JobClassifier {
        override val rulesVersion: String = ruleSet.version

        override fun classify(input: ClassificationInput, target: RuleTarget): JobClassification =
            RuleBasedJobClassifier.classifyWith(ruleSet, input, target)
    }

    private var nextSkillId = 1L

    private fun alias(skill: String, alias: String, policy: MatchPolicy = MatchPolicy.WORD, skillId: Long? = null) =
        SkillAliasEntry(skillId ?: skillIds.getOrPut(skill) { nextSkillId++ }, skill, alias, alias.lowercase(), policy)

    private val skillIds = mutableMapOf<String, Long>()

    val aliases: List<SkillAliasEntry> by lazy {
        listOf(
            alias("Java", "java"),
            alias("Kotlin", "kotlin"),
            alias("Spring Boot", "spring boot"),
            alias("Spring Boot", "spring"),
            alias("PostgreSQL", "postgresql"),
            alias("PostgreSQL", "postgres"),
            alias("Kafka", "kafka"),
            alias("Kubernetes", "kubernetes"),
            alias("React", "react"),
            alias("TypeScript", "typescript"),
            alias("Next.js", "next.js"),
            alias("Node.js", "node.js"),
            alias("Node.js", "nodejs"),
            alias("Python", "python"),
            alias("Spark", "spark"),
            alias("Airflow", "airflow"),
            alias("dbt", "dbt"),
            alias("Flutter", "flutter"),
            alias("Dart", "dart"),
            alias("SQL", "sql"),
            alias("C#", "c#"),
            alias("C++", "c++"),
            alias("Go", "golang"),
            alias("Go", "Go", MatchPolicy.CONTEXT_REQUIRED),
            alias("R", "R", MatchPolicy.CONTEXT_REQUIRED),
            alias("C", "C", MatchPolicy.CONTEXT_REQUIRED),
            alias("Ruby", "ruby", MatchPolicy.DISABLED)
        )
    }
}
