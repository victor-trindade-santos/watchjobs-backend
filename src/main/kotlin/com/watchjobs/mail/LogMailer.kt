package com.watchjobs.mail

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

data class LoggedMail(val to: String, val subject: String, val text: String)

@Component
@ConditionalOnProperty(name = ["app.mail.mode"], havingValue = "log")
class LogMailer : Mailer {
    private val logger = LoggerFactory.getLogger(LogMailer::class.java)

    @Volatile
    private var stored: LoggedMail? = null

    val last: LoggedMail?
        get() = stored

    override fun send(to: String, subject: String, text: String) {
        stored = LoggedMail(to, subject, text)
        logger.info("E-mail (modo log) para {}: {} — {}", to, subject, text)
    }
}
