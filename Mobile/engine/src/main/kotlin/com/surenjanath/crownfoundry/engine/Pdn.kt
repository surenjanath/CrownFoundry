package com.surenjanath.crownfoundry.engine

/**
 * Portable Draughts Notation - the format every other draughts program can read.
 *
 * Export exists so a game does not end its life inside this app. A player who wants to show a game
 * to someone, paste it into an analysis site, or keep it after uninstalling should be able to; a
 * move list trapped in a private JSON file is a game you do not really own.
 *
 * The import side is deliberately minimal - enough to read back what this writes, and enough to let
 * the round-trip test prove the export is well-formed rather than merely plausible. It is not a
 * general PDN reader: variations and numeric annotation glyphs are skipped rather than modelled.
 *
 * Two things about draughts PDN that catch people out, both handled here:
 *
 * * **Black moves first**, so move number 1 holds Black's move and then White's - the opposite
 *   pairing to chess.
 * * **`Result` is written from White's side** anyway: `1-0` is a White win, `0-1` a Black win. The
 *   human is Black in this app, so a game you won exports as `0-1`.
 */
object Pdn {

    const val RESULT_WHITE = "1-0"
    const val RESULT_BLACK = "0-1"
    const val RESULT_DRAW = "1/2-1/2"
    const val RESULT_UNFINISHED = "*"

    /** GameType 20 is English draughts / American checkers, and only those rules. */
    private const val GAME_TYPE_ENGLISH = "20"

    private const val LINE_WIDTH = 79

    data class Tags(
        val event: String = "CrownFoundry",
        val site: String = "CrownFoundry",
        /** PDN dates are `YYYY.MM.DD`, with `??` for parts that are not known. */
        val date: String = "????.??.??",
        val round: String = "-",
        val white: String = "CrownFoundry",
        val black: String = "Player",
        val result: String = RESULT_UNFINISHED,
        val rules: VariantRules = VariantRules.DEFAULT,
        /** Anything else worth recording, appended in order after the seven-tag roster. */
        val extra: List<Pair<String, String>> = emptyList()
    )

    fun resultOf(winner: Int?): String = when (winner) {
        WHITE -> RESULT_WHITE
        BLACK -> RESULT_BLACK
        DRAW_RESULT -> RESULT_DRAW
        else -> RESULT_UNFINISHED
    }

    /** Render a game. [moves] are canonical notations from the opening position, in order. */
    fun export(moves: List<String>, tags: Tags = Tags()): String {
        val out = StringBuilder()

        tag(out, "Event", tags.event)
        tag(out, "Site", tags.site)
        tag(out, "Date", tags.date)
        tag(out, "Round", tags.round)
        tag(out, "White", tags.white)
        tag(out, "Black", tags.black)
        tag(out, "Result", tags.result)

        // Claiming GameType 20 for a game that was not played under English rules would make the
        // file wrong in a way a reader cannot detect. Say which variant it actually was instead.
        if (tags.rules == VariantRules.ENGLISH) {
            tag(out, "GameType", GAME_TYPE_ENGLISH)
        } else {
            tag(out, "Variant", describe(tags.rules))
        }
        for ((name, value) in tags.extra) tag(out, name, value)

        out.append('\n')
        out.append(movetext(moves, tags.result))
        out.append('\n')
        return out.toString()
    }

    /** `11-15 22-18 15x22 …` paired into numbered moves and wrapped to a sane width. */
    fun movetext(moves: List<String>, result: String = RESULT_UNFINISHED): String {
        val tokens = ArrayList<String>(moves.size + moves.size / 2 + 1)
        for ((index, move) in moves.withIndex()) {
            // Black moves first, so a new number opens on every even index.
            if (index % 2 == 0) tokens.add("${index / 2 + 1}.")
            tokens.add(move)
        }
        tokens.add(result)

        val out = StringBuilder()
        var width = 0
        for (token in tokens) {
            if (width > 0 && width + 1 + token.length > LINE_WIDTH) {
                out.append('\n')
                width = 0
            } else if (width > 0) {
                out.append(' ')
                width += 1
            }
            out.append(token)
            width += token.length
        }
        return out.toString()
    }

    /**
     * The moves out of a PDN document.
     *
     * Tolerant by design: comments, variations, annotation glyphs, move numbers and the result
     * token are all discarded, and anything left that does not parse as a move string is skipped
     * rather than thrown over. A file this cannot read is more useful half-read than refused.
     */
    fun movesOf(pdn: String): List<String> {
        val body = stripTags(pdn)
        val cleaned = stripComments(body)

        val moves = ArrayList<String>()
        for (raw in cleaned.split(' ', '\n', '\t', '\r')) {
            val token = raw.trim().trimEnd('!', '?')
            if (token.isEmpty()) continue
            if (token in RESULTS) continue
            if (token.endsWith('.') || token.all { it.isDigit() }) continue
            if (token.startsWith('$')) continue

            try {
                parseMoveString(token)
            } catch (failure: MalformedMove) {
                continue
            }
            moves.add(token)
        }
        return moves
    }

    /** The tag pairs, in the order they appear. */
    fun tagsOf(pdn: String): Map<String, String> {
        val tags = LinkedHashMap<String, String>()
        for (line in pdn.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) continue
            val inner = trimmed.substring(1, trimmed.length - 1)
            val quote = inner.indexOf('"')
            if (quote < 0 || !inner.endsWith("\"")) continue
            val name = inner.substring(0, quote).trim()
            if (name.isEmpty()) continue
            tags[name] = unescape(inner.substring(quote + 1, inner.length - 1))
        }
        return tags
    }

    // --- internals -------------------------------------------------------------------------

    private val RESULTS = setOf(RESULT_WHITE, RESULT_BLACK, RESULT_DRAW, RESULT_UNFINISHED)

    private fun tag(out: StringBuilder, name: String, value: String) {
        out.append('[').append(name).append(" \"").append(escape(value)).append("\"]\n")
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unescape(value: String) = value.replace("\\\"", "\"").replace("\\\\", "\\")

    private fun describe(rules: VariantRules): String = buildString {
        append(if (rules.flyingKings) "flying kings" else "short kings")
        append(", men capture ")
        append(if (rules.menCaptureBackwards) "both ways" else "forward only")
        append(", captures ")
        append(if (rules.mandatoryCapture) "mandatory" else "optional")
    }

    private fun stripTags(pdn: String): String = pdn.lineSequence()
        .filterNot { it.trim().startsWith("[") }
        .joinToString("\n")

    /** Drops `{ … }` comments, `; …` line comments and `( … )` variations, nesting included. */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var braces = 0
        var parens = 0
        var lineComment = false

        for (ch in text) {
            when {
                lineComment -> if (ch == '\n') {
                    lineComment = false
                    out.append(' ')
                }

                ch == '{' -> braces++
                ch == '}' -> if (braces > 0) braces--
                braces > 0 -> Unit

                ch == '(' -> parens++
                ch == ')' -> if (parens > 0) parens--
                parens > 0 -> Unit

                ch == ';' -> lineComment = true
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}
