package com.offlinekeyboard.ime.candidates

import kotlin.math.ln

/**
 * One suggestion bar holding emoji and Chinese at once, ranked against each other.
 *
 * The bar used to be two bars wearing the same paint: in English it showed emoji, in Chinese it
 * showed characters, and which one you got was decided by the subtype rather than by what the
 * letters could plausibly mean. That is the wrong cut. `niuroumian` is not ambiguous -- no emoji
 * is named anything like it and it is a perfectly good Chinese word -- while `ha` genuinely is,
 * and `happy` genuinely is not. Only a score can tell those three cases apart, so all three
 * kinds of suggestion are scored on one scale here and the bar simply shows the best of them.
 *
 * **The scale is log-probability**, in nats, which is what the pinyin decoder already produces
 * (see [com.offlinekeyboard.ime.pinyin.Decoder]): a candidate's score is `ln P(candidate)`, so a
 * thing ten times as likely scores `ln 10` higher. Putting emoji on that scale is the whole
 * trick, and [emojiScore] is where it happens.
 *
 * The scale decides only *where emoji go*. The Chinese candidates are never re-sorted on it:
 * they keep the decoder's order -- whole-input readings, then prefixes longest first, then
 * characters -- which is the order the Chinese subtypes show, so the two bars agree about
 * Chinese by construction. See [rank] for why re-sorting them was the bug rather than the
 * feature.
 */
object UnifiedCandidates {

    /**
     * Where a suggestion came from. The bar draws the three differently -- an emoji is a glyph
     * and Chinese is text -- and committing them differs too, so the kind travels with the text
     * rather than being re-derived by inspecting the characters.
     */
    enum class Kind { EMOJI, CHINESE, LATIN }

    /**
     * One suggestion, with the score that put it where it is.
     *
     * [score] is kept rather than discarded after sorting because it is the only way to explain
     * a bar after the fact: `tools/rank_preview.py` prints it, and a surprising order is then a
     * number to argue with instead of a mystery.
     */
    data class Suggestion(
        val text: String,
        val kind: Kind,
        val score: Float,
        /**
         * How many characters of typed input this replaces when tapped.
         *
         * Carried per suggestion because the kinds disagree: an emoji for `pizza` replaces all
         * five letters, while a Chinese candidate may consume only the first syllable of
         * `beijingdaxue` and leave the rest composing.
         */
        val consumes: Int,
        /** The decoder's candidate behind a Chinese suggestion, for learning the choice. */
        val source: Scored? = null,
    )

    /**
     * Places emoji around the Chinese candidates for the same typed letters.
     *
     * **The Chinese candidates keep exactly the order the decoder gave them**, which is the
     * order the Chinese subtypes show: every reading of the whole input first, then words
     * covering the start of it, longest first, then single characters for the first syllable.
     * That is the shape of the iOS bar and of every mainstream IME (Rime puts its sentence
     * first and phrases after it by length; AOSP PinyinIME's candidate 0 is always the full
     * sentence), and it is the same bar in both languages -- the English bar is not allowed a
     * second opinion about which Chinese reading is best.
     *
     * It used to have one. Everything was re-sorted on a single score with a fixed charge for
     * letters a candidate left unexplained, and that charge was capped while a sentence's cost
     * grows with every syllable: for `tadebabahentaoyanwo`, 他的 (-23.0, four letters of
     * nineteen) beat 他的爸爸很讨厌我 (-39.4, all of them), and the filler rule then deleted the
     * sentence for being 16 nats behind. Ordering by what a candidate *covers* rather than by
     * a score across different spans is what removes that whole class of failure.
     *
     * What this function still decides is the only thing Chinese mode never has to: whether
     * the letters are Chinese at all, and so where the emoji go. Each Chinese candidate is
     * given a sort key on the shared log-probability scale, clamped so it never exceeds the key
     * of the one before it -- which lets an ordinary merge interleave emoji without reordering a
     * single Chinese entry. And when the best Chinese reading is [CHINESE_GATE] nats behind the
     * best emoji (`happy`, `pizza`, `the` -- English words that also parse as pinyin), the
     * Chinese candidates are left off entirely rather than trailing the bar.
     *
     * @param query the letters typed, lowercased.
     * @param emojiHits emoji matching [query], best first, as [EmojiIndex.search] returns them.
     * @param chinese Chinese candidates for [query], **in decoder order**, with decoder scores.
     */
    fun rank(
        query: String,
        emojiHits: List<String>,
        chinese: List<Scored>,
        englishScore: Float = NOT_ENGLISH,
        limit: Int = 32,
    ): List<Suggestion> {
        val emoji = emojiHits.mapIndexed { rank, text ->
            Suggestion(text, Kind.EMOJI, emojiScore(rank, query), query.length)
        }

        // How much the letters being an ordinary English word argues against reading them as
        // pinyin. See [ENGLISH_EVIDENCE].
        val englishPenalty = if (englishScore <= NOT_ENGLISH) {
            0f
        } else {
            (englishScore - NOT_ENGLISH).coerceAtLeast(0f) * ENGLISH_EVIDENCE
        }

        var ceiling = Float.POSITIVE_INFINITY
        val zh = chinese.map { candidate ->
            val coverage = if (query.isEmpty()) {
                0f
            } else {
                (candidate.consumes.toFloat() / query.length).coerceIn(0f, 1f)
            }
            val key = minOf(ceiling, chineseScore(candidate.score, coverage) - englishPenalty)
            ceiling = key
            Suggestion(candidate.text, Kind.CHINESE, key, candidate.consumes, candidate)
        }

        val showChinese = zh.isNotEmpty() &&
            (emoji.isEmpty() || zh.first().score >= emoji.first().score - CHINESE_GATE)
        val merged = merge(emoji, if (showChinese) zh else emptyList())
        return merged.take(limit)
    }

    /**
     * Merges two lists that are each already in their final order, taking whichever head scores
     * higher. Neither list is reordered internally; ties go to the emoji, which were there first.
     */
    private fun merge(a: List<Suggestion>, b: List<Suggestion>): List<Suggestion> {
        val out = ArrayList<Suggestion>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            out += when {
                j >= b.size -> a[i++]
                i >= a.size -> b[j++]
                a[i].score >= b[j].score -> a[i++]
                else -> b[j++]
            }
        }
        return out
    }

    /**
     * A Chinese candidate as the decoder produced it: text, its log-probability, and its span.
     *
     * [ids] are the syllables it stands for, so that choosing it from the English bar can be
     * learned exactly as choosing it from the Chinese bar is.
     */
    class Scored(
        val text: String,
        val score: Float,
        val consumes: Int,
        val ids: IntArray = IntArray(0),
    )

    /**
     * What the typed word becomes when the Chinese candidate [text] is picked for it.
     *
     * A candidate stands for the **start** of the word -- 他的 for `tade` out of
     * `tadebabahentaoyanwo` -- so the letters after it stay, to be answered next:
     * `他的babahentaoyanwo`. Counting those letters off the caret end instead is what produced
     * `tadebabahentaoy他的`: the right number of letters, deleted from the wrong side.
     */
    fun replacementFor(word: String, text: String, consumes: Int): String =
        text + word.substring(consumes.coerceIn(0, word.length))

    /**
     * An emoji's log-probability, on the same scale as everything else.
     *
     * **This is the function that makes one ranking possible**, and the scale is the whole of
     * it. The pinyin decoder scores a candidate as `ln P(text)` against a corpus of running
     * text; an emoji must therefore be scored as the probability that *a token of text is this
     * emoji*, not as its share of emoji or as a position in a list. Those two readings differ by
     * a constant -- around 5.7 nats -- and using the wrong one is what a "unified" ranking
     * usually gets wrong: emoji land near 0, Chinese near -8, and the bar is emoji-only forever
     * regardless of what the letters meant.
     *
     * Two facts fix the constant. Emoji are roughly [EMOJI_TOKEN_SHARE] of tokens in casual
     * text, and their own frequencies are Zipfian -- which is also exactly how the asset is
     * ordered, by Unicode's frequency study, so rank is the only frequency signal available and
     * it is the right one. Together:
     *
     *     P(emoji at rank r) = EMOJI_TOKEN_SHARE * (1/(r+1)) / H
     *
     * with `H` the harmonic normaliser over the catalogue. That puts the best match for a word
     * around -7.8 and the tenth around -10.1, against 我 at -4.2, 哈 at -7.7 and 牛肉面 at -11.0.
     * The orderings asked for then fall out of the arithmetic rather than out of a rule:
     * `happy` is emoji-only because no Chinese reading covers it at all, `niuroumian` is
     * Chinese-only because no emoji is named anything like it, and `ha` genuinely mixes.
     */
    private fun emojiScore(rank: Int, query: String): Float =
        ln(EMOJI_TOKEN_SHARE) - ln((rank + 1).toFloat()) - ln(EMOJI_HARMONIC) +
            lengthBonus(query)

    /**
     * How much less an emoji match means when the query is very short.
     *
     * A one- or two-letter query matches emoji by bare prefix, which is weak evidence of intent:
     * `h` prefixes dozens of names without suggesting any of them. Longer queries need no such
     * discount -- by four letters a name match is deliberate -- so this is zero there rather
     * than a curve, and it is deliberately small: at two letters `ha` should still lead with
     * laughing faces, just not so far ahead that 哈 cannot appear beside them.
     */
    private fun lengthBonus(query: String): Float =
        when {
            query.length <= 1 -> -1.2f
            query.length == 2 -> -0.5f
            else -> 0f
        }

    /**
     * A Chinese candidate's score, discounted by how much of the input it explains.
     *
     * The decoder's score is already `ln P(text)`, so the only adjustment is coverage: a reading
     * that consumes every letter typed is taken at face value, and one that explains a prefix is
     * charged for the letters it leaves unaccounted for. That charge is what stops `happy` from
     * offering 哈 -- `ha` is a fine syllable, but it explains two of five letters, and the
     * remaining `ppy` is not pinyin at all.
     *
     * Scaled by [COVERAGE_PENALTY] per unexplained fraction, which at full miss is far larger
     * than the gap between a common and a rare word -- uncovered input is a stronger signal than
     * frequency, because it means the user is probably not typing pinyin in the first place.
     */
    private fun chineseScore(decoderScore: Float, coverage: Float): Float =
        decoderScore - (1f - coverage) * COVERAGE_PENALTY

    /**
     * What fraction of tokens in casual text are emoji. See [emojiScore].
     *
     * The one empirical constant the unified ranking rests on, and the only place the two kinds
     * of suggestion are calibrated against each other -- so it is worth being explicit that it
     * is an estimate, of the right order rather than measured here. Raising it moves every
     * emoji up against every Chinese candidate uniformly; it does not reorder either kind
     * internally. 1/300 is a conservative reading of emoji-bearing chat text.
     */
    private const val EMOJI_TOKEN_SHARE = 1f / 300f

    /**
     * Harmonic normaliser for the Zipf over the emoji catalogue: `sum(1/r)` for r = 1..1914,
     * the number of entries in `emoji_en.tsv`.
     *
     * Constant rather than computed because the catalogue is a shipped asset that changes only
     * when it is regenerated, and the sum is flat in its tail -- a few hundred entries either
     * way move this by hundredths of a nat.
     */
    private const val EMOJI_HARMONIC = 8.1f

    /**
     * Charged for input a Chinese reading does not explain, in nats. See [chineseScore].
     *
     * Only ever used to place emoji relative to the Chinese candidates -- never to reorder
     * those -- so a cap on it can no longer let a prefix outrank the sentence it begins.
     */
    private const val COVERAGE_PENALTY = 18f

    /**
     * How far the best Chinese reading may sit behind the best emoji before the letters are
     * taken not to be Chinese at all, in nats: 7 is "about a thousand times less likely".
     *
     * Only consulted when there are emoji to compare against, so input that matches no emoji
     * (`niuroumian`, `tadebabahentaoyanwo`) always shows its Chinese. The cases it exists for
     * are English words that also parse as pinyin -- `happy` (哈皮朋友, 15 nats behind),
     * `the`, `pizza` -- whose Chinese readings would otherwise trail every English bar.
     */
    private const val CHINESE_GATE = 7f

    /**
     * The log-probability below which a query is not treated as an English word at all.
     *
     * Callers pass `ln P(word)` from the English lexicon, or this when the letters are not in it.
     * -14 is below the rarest lexicon entry, so "absent" and "vanishingly rare" behave alike.
     */
    const val NOT_ENGLISH = -14f

    /**
     * How strongly the letters being an English word argues against a Chinese reading of them.
     *
     * This is the term that keeps the bar honest for English, and it is needed because pinyin is
     * written with the same 26 letters: `you`, `take`, `like`, `women` and `wo` are all ordinary
     * English words *and* valid pinyin, and the Chinese reading of them is often the commoner
     * string in isolation -- 有 beats 牛肉面 on frequency by six nats, because one character is
     * commoner than one three-character dish.
     *
     * Frequency alone therefore gets this exactly backwards, and the missing evidence is not
     * about Chinese at all: it is that the letters are *already a word in the language being
     * typed*. Someone typing `you` in an English field has almost certainly finished typing it.
     * So the penalty is proportional to how common the English word is -- scaled from the floor
     * at [NOT_ENGLISH], so a rare word costs a Chinese candidate little and `the` costs it a
     * great deal -- and words that are not English at all (`niuroumian`, `hao`, `xiexie`) are
     * charged nothing whatsoever.
     *
     * At 1.0 the term is exactly the English word's own log-probability, which is the principled
     * value: the two readings are then compared as the competing hypotheses they are.
     */
    private const val ENGLISH_EVIDENCE = 1.0f
}
