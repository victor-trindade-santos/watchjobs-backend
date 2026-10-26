package com.watchjobs.mail

interface Mailer {
    fun send(to: String, subject: String, text: String)
}
