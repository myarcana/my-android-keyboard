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
            Row(
                listOf(Key("mode_symbols", "#+=", widthUnits = 1.5f, type = KeyType.MODE_SWITCH)) +
                    listOf(".", ",", "?", "!", "'").map { c(it) } +
                    listOf(Key("backspace", "", widthUnits = 1.5f, type = KeyType.BACKSPACE)),
            ),
            bottomRow("ABC", "mode_abc"),
        ),
    )

    /** The iOS "#+=" plane. */
    val SYMBOLS = Layout(
        id = "symbols",
        rows = listOf(
            Row(listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "=").map { c(it) }),
            Row(listOf("_", "\\", "|", "~", "<", ">", "\u20ac", "\u00a3", "\u00a5", "\u2022").map { c(it) }),
            Row(
                listOf(Key("mode_numbers", "123", widthUnits = 1.5f, type = KeyType.MODE_SWITCH)) +
                    listOf(".", ",", "?", "!", "'").map { c(it) } +
                    listOf(Key("backspace", "", widthUnits = 1.5f, type = KeyType.BACKSPACE)),
            ),
            bottomRow("ABC", "mode_abc"),
        ),
    )
}
