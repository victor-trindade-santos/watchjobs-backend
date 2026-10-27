package com.watchjobs.profile.importer

// Leitor de CSV (RFC 4180): aspas, aspas duplicadas e quebras de linha dentro de campos.
object CsvParser {
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = if (text.startsWith('\uFEFF')) 1 else 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        row += field.toString()
                        field.setLength(0)
                    }
                    '\r' -> {}
                    '\n' -> {
                        row += field.toString()
                        field.setLength(0)
                        rows += row
                        row = mutableListOf()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row += field.toString()
            rows += row
        }
        return rows.filterNot { r -> r.all { it.isBlank() } }
    }
}
