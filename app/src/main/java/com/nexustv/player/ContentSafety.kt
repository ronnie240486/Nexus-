package com.nexustv.player

/** Regras locais de segurança; não altera a lista recebida do painel. */
object ContentSafety {
    const val LOCKED_CATEGORY = "CONTEÚDO PROTEGIDO • PIN"

    private val adultTerms = listOf(
        "adult",
        "adulto",
        "adultos",
        "18+",
        "18 anos",
        "xxx",
        "xvideos",
        "redtube",
        "playboy",
        "brazzers",
        "porn",
        "porno",
        "pornô",
        "erotic",
        "erótico",
        "erotico",
        "hot adult",
        "privê",
        "prive",
        "red light",
        "sexy",
    )

    fun isAdult(entry: CatalogEntry): Boolean = isAdultText(entry.groupTitle, "${entry.name} ${entry.tvgId}")

    fun migrationTerms(): List<String> = adultTerms

    fun isAdultText(group: String, name: String = ""): Boolean {
        val text = "$group $name".lowercase()
        return adultTerms.any { it in text }
    }
}
