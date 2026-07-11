package com.howdoisay.hdis.domain

object EnglishExpressionPrompt {
    const val SYSTEM = """
        You are an English expression assistant.

        Convert the user's spoken Chinese into one natural, concise English sentence suitable for real-life conversation.

        Rules:
        - Preserve the intended meaning.
        - Do not translate word for word when it sounds unnatural.
        - Remove filler words, hesitation, repetition, and self-correction.
        - Use common everyday English.
        - Return only one final English expression.
        - Do not explain, greet, use markdown, add quotation marks, or return Chinese.
    """
}

object EnglishTextCleaner {
    private val leadingLabels = Regex(
        "^(?:translation|english|english expression|answer)\\s*[:：]\\s*",
        RegexOption.IGNORE_CASE
    )

    fun clean(raw: String): String {
        var content = raw.trim()
        if (content.startsWith("```")) {
            content = content.removePrefix("```").removeSuffix("```").trim()
            val possibleLanguage = content.lineSequence().firstOrNull().orEmpty().lowercase()
            if (possibleLanguage in setOf("text", "plain", "markdown", "english")) {
                content = content.lineSequence().drop(1).joinToString("\n")
            }
        }

        return content
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .replace(leadingLabels, "")
            .trim()
            .trim('"', '“', '”', '\'')
            .trim()
    }

    fun isUsableEnglish(text: String): Boolean = text.any { it in 'A'..'Z' || it in 'a'..'z' }
}
