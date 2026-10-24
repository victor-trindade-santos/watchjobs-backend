package com.watchjobs.entity.enums

import com.fasterxml.jackson.annotation.JsonAlias

enum class WorkMode {
    REMOTE,
    HYBRID,
    @JsonAlias("ONSITE")
    ON_SITE
}


enum class ExperienceLevel {
    INTERN,
    TRAINEE,
    JUNIOR,
    MID,
    SENIOR,
    SPECIALIST,
    LEAD,
}

enum class JobType {
    CLT,
    PJ,
    INTERNSHIP,
    FREELANCE,
    TEMPORARY
}

enum class SavedJobStatus {
    SAVED,
    PLANNING_TO_APPLY,
    APPLIED,
    IN_PROCESS,
    OFFER_RECEIVED,
    ACCEPTED,
    REJECTED,
    WITHDRAWN
}