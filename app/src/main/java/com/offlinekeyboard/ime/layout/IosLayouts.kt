package com.offlinekeyboard.ime.layout

/**
 * The iPhone QWERTY layout, with iPadOS flick-down secondaries layered on top.
 *
 * The secondaries are the iPadOS arrangement: digits across the top row, then the symbols
 * iPadOS assigns to the home and bottom rows. iOS on iPhone has no flick gesture at all, so
 * this is deliberately a blend of the two -- iPhone geometry, iPad flick behaviour -- which is
 * what was asked for.
 *
 * Accent lists are the iOS long-press popups.
 */
object IosLayouts {

    private fun c(
        primary: String,
        secondary: String? = null,
        accents: List<String> = emptyList(),
    ) = Key(id = primary, primary = primary, secondary = secondary, accents = accents)

    val QWERTY_LOWER = Layout(
        id = "en_qwerty_lower",
        rows = listOf(
            Row(
                listOf(
                    c("q", "1"),
                    c("w", "2"),
                    c("e", "3", listOf("è", "é", "ê", "ë", "ē", "ė", "ę")),
                    c("r", "4"),
                    c("t", "5"),
                    c("y", "6", listOf("ÿ")),
                    c("u", "7", listOf("û", "ü", "ù", "ú", "ū")),
                    c("i", "8", listOf("î", "ï", "í", "ī", "į", "ì")),
                    c("o", "9", listOf("ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ")),
                    c("p", "0"),
                ),
            ),
            Row(
                listOf(
                    c("a", "@", listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā")),
                    c("s", "#", listOf("ß", "ś", "š")),
                    c("d", "$"),
                    c("f", "&"),
                    c("g", "*"),
                    c("h", "("),
                    c("j", ")"),
                    c("k", "'"),
                    c("l", "\"", listOf("ł")),
                ),
            ),
            Row(
                listOf(
                    Key("shift", "", widthUnits = 1.5f, type = KeyType.SHIFT),
                    c("z", "%", listOf("ž", "ź", "ż")),
                    c("x", "-"),
                    c("c", "+", listOf("ç", "ć", "č")),
                    c("v", "="),
                    c("b", "/"),
                    c("n", ";", listOf("ñ", "ń")),
                    c("m", ":"),
                    Key("backspace", "", widthUnits = 1.5f, type = KeyType.BACKSPACE),
                ),
            ),
            Row(
                listOf(
                    Key("mode_123", "123", widthUnits = 1.5f, type = KeyType.MODE_SWITCH),
                    Key("globe", "", widthUnits = 1.25f, type = KeyType.GLOBE),
                    Key("mic", "", widthUnits = 1.25f, type = KeyType.MIC),
                    Key("space", " ", widthUnits = 4.4f, type = KeyType.SPACE),
                    Key("return", "\n", widthUnits = 2.5f, type = KeyType.RETURN),
                ),
            ),
        ),
    )

    /** Shift applied. Secondaries and accents are inherited from the lowercase layout. */
    val QWERTY_UPPER = Layout(
        id = "en_qwerty_upper",
        rows = QWERTY_LOWER.rows.map { row ->
            Row(
                row.keys.map { key ->
                    if (key.type == KeyType.CHARACTER) {
                        key.copy(
                            primary = key.primary.uppercase(),
                            accents = key.accents.map { it.uppercase() },
                        )
                    } else {
                        key
                    }
                },
            )
        },
    )

    /**
     * Width of one of the five punctuation keys on the number and symbol planes, in key units.
     *
     * The third row is the only row whose key *count* changes between planes: nine on the letter
     * plane, seven here. iOS holds the two shoulder keys -- shift or the mode switch on the left,
     * backspace on the right -- in exactly the same rectangle across all three planes and widens
     * the keys between them to take up the slack, so switching planes never moves the key a thumb
     * is already travelling towards. Measured off iOS at 3x: the shoulders sit at the same pixels
     * in all three screenshots, and the band between them spans the same 802px whether it holds
     * seven letters or five punctuation marks.
     *
     * That is one equation. Setting this row's width equal to the letter row's,
     *
     *     2 shoulders + 5 punctuation + 6 gaps  ==  2 shoulders + 7 letters + 8 gaps
     *
     * the shoulders cancel and leave 5w == 7 + 2g, so w is 1.4 key units plus two fifths of a
     * gap. Derived rather than written down as a number so that it still holds if [Metrics] is
     * ever recalibrated -- a literal would quietly stop lining the shoulders up.
     */
    private val PUNCTUATION_UNITS = 1.4f + 0.4f * (Metrics.KEY_GAP / Metrics.KEY_WIDTH)

    /** The shift/backspace row of the number and symbol planes, which differ only in their mode key. */
    private fun punctuationRow(modeLabel: String, modeId: String) = Row(
        listOf(Key(modeId, modeLabel, widthUnits = 1.5f, type = KeyType.MODE_SWITCH)) +
            listOf(".", ",", "?", "!", "'").map {
                c(it).copy(widthUnits = PUNCTUATION_UNITS)
            } +
            listOf(Key("backspace", "", widthUnits = 1.5f, type = KeyType.BACKSPACE)),
    )

    private fun bottomRow(modeLabel: String, modeId: String) = Row(
        listOf(
            Key(modeId, modeLabel, widthUnits = 1.5f, type = KeyType.MODE_SWITCH),
            Key("globe", "", widthUnits = 1.25f, type = KeyType.GLOBE),
            Key("mic", "", widthUnits = 1.25f, type = KeyType.MIC),
            Key("space", " ", widthUnits = 4.4f, type = KeyType.SPACE),
            Key("return", "\n", widthUnits = 2.5f, type = KeyType.RETURN),
        ),
    )

    /** The iOS "123" plane. */
    val NUMBERS = Layout(
        id = "numbers",
        rows = listOf(
            Row("1234567890".map { c(it.toString()) }),
            Row(listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\"").map { c(it) }),
            punctuationRow("#+=", "mode_symbols"),
            bottomRow("ABC", "mode_abc"),
        ),
    )

    /** The iOS "#+=" plane. */
    val SYMBOLS = Layout(
        id = "symbols",
        rows = listOf(
            Row(listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").map { c(it) }),
            Row(listOf("_", "\\", "|", "~", "<", ">", "\u20ac", "\u00a3", "\u00a5", "\u2022").map { c(it) }),
            punctuationRow("123", "mode_numbers"),
            bottomRow("ABC", "mode_abc"),
        ),
    )

    /**
     * The plane a mode-switch key leads to, or null if the id names no plane.
     *
     * Lives here, beside the rows that spell those ids, because it is the other half of the same
     * fact: a mode key is only meaningful as a pair of a label and a destination, and splitting
     * the two across files is how they drifted apart. Two mode keys are on screen at once on the
     * number and symbol planes -- "#+=" or "123" on the third row and "ABC" on the bottom -- so
     * the destination cannot be inferred from the current plane alone.
     */
    fun planeFor(keyId: String): Layout? = when (keyId) {
        "mode_abc" -> QWERTY_LOWER
        "mode_123", "mode_numbers" -> NUMBERS
        "mode_symbols" -> SYMBOLS
        else -> null
    }

    /** Every layout, for anything replaying a recording that names one. */
    val ALL = listOf(QWERTY_LOWER, QWERTY_UPPER, NUMBERS, SYMBOLS)

    fun byId(id: String): Layout? = ALL.firstOrNull { it.id == id }
}
