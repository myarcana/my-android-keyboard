package com.offlinekeyboard.ime.autofill

import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Size
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.ImageView
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.offlinekeyboard.ime.R
import com.offlinekeyboard.ime.layout.Metrics
import com.offlinekeyboard.ime.view.keyboardTheme

/**
 * Builds the request that makes password managers offer to fill from the suggestion strip.
 *
 * Nothing typed leaves the keyboard through this. An inline suggestion is a surface rendered by
 * the password manager's own process and handed to us as an opaque view: this app cannot read
 * what the chip says, and tapping one makes that manager fill the field directly rather than
 * routing a credential through here. What we send outwards is only the styling below -- colours
 * and sizes -- so the offline guarantee is untouched even though the chips come from elsewhere.
 */
@RequiresApi(Build.VERSION_CODES.R)
object InlineAutofill {

    /**
     * How many chips to ask for.
     *
     * More than fit on screen on purpose: the strip scrolls, and a manager holding six logins
     * for one site should be able to show them. The cost of a high number is only the work the
     * manager does preparing suggestions it may never draw.
     */
    private const val MAX_SUGGESTIONS = 6

    /** Breathing room above and below a chip, as a fraction of the strip it sits in. */
    private const val CHIP_INSET_FRACTION = 0.09f

    /** Chip text sizes, in sp. The subtitle is the username under the site name. */
    private const val TITLE_SP = 15f
    private const val SUBTITLE_SP = 12f

    /**
     * The narrowest a chip may be drawn.
     *
     * Wide enough that a manager cannot hand back a chip too small to read the site name in;
     * expressed against the strip's height rather than in dp so it tracks a resized keyboard.
     */
    private const val MIN_CHIP_WIDTH_IN_HEIGHTS = 2.2f

    /**
     * The style bundle and size bounds the password manager renders against.
     *
     * Returns null when the width is not yet known, which is a real state: the system asks for
     * this before the input view has been measured on the very first show. Declining once is
     * better than answering with a guessed size the chips would then be clipped to -- the system
     * asks again for the next field.
     */
    fun request(context: Context, keyboardWidthPx: Int): InlineSuggestionsRequest? {
        val chipHeight = chipHeightPx(keyboardWidthPx)
        if (chipHeight <= 0) return null
        val theme = keyboardTheme(context.resources)

        val style = InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(
                ViewStyle.Builder()
                    // A drawable rather than a colour: the chip wants the keyboard's rounded
                    // shape, and the remote renderer can resolve one of our resources by name.
                    .setBackground(
                        Icon.createWithResource(context, R.drawable.inline_suggestion_chip)
                    )
                    .setPadding(chipHeight / 3, 0, chipHeight / 3, 0)
                    .build()
            )
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(theme.text)
                    .setTextSize(TITLE_SP)
                    .build()
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(theme.secondaryText)
                    .setTextSize(SUBTITLE_SP)
                    .build()
            )
            .setStartIconStyle(
                ImageViewStyle.Builder()
                    .setScaleType(ImageView.ScaleType.FIT_CENTER)
                    .build()
            )
            .build()

        val styles = UiVersions.newStylesBuilder().addStyle(style).build()

        val spec = InlinePresentationSpec.Builder(
            Size((chipHeight * MIN_CHIP_WIDTH_IN_HEIGHTS).toInt(), chipHeight),
            // A chip may be as wide as the keyboard; anything longer is the manager's to
            // ellipsise, and the strip scrolls to reach the ones past the edge.
            Size(keyboardWidthPx, chipHeight),
        ).setStyle(styles).build()

        // One spec for a list of chips: the platform reuses the last spec for every suggestion
        // beyond the ones it was given, so all six come out the same shape.
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(MAX_SUGGESTIONS)
            .build()
    }

    /**
     * The height chips are both requested at and laid out at.
     *
     * One function for both so they cannot disagree: a chip inflated at a height the manager was
     * not asked for comes back scaled or clipped.
     */
    fun chipHeightPx(keyboardWidthPx: Int): Int {
        if (keyboardWidthPx <= 0) return 0
        val strip = Metrics.stripTouchHeightPx(keyboardWidthPx.toFloat())
        return (strip * (1f - 2 * CHIP_INSET_FRACTION)).toInt()
    }
}
