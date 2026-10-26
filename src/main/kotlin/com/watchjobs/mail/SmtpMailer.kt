package com.watchjobs.mail

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["app.mail.mode"], havingValue = "smtp", matchIfMissing = true)
class SmtpMailer(
    private val mailSender: JavaMailSender,
    @Value("\${app.mail.from}") private val from: String
) : Mailer {
    override fun send(to: String, subject: String, text: String) {
        val message = SimpleMailMessage()
        message.from = from
        message.setTo(to)
        message.subject = subject
        message.text = text
        mailSender.send(message)
    }
}
