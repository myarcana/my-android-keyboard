package com.offlinekeyboard.ime

import android.content.Context
import android.os.IBinder
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import com.offlinekeyboard.ime.layout.Key
import com.offlinekeyboard.ime.layout.KeyType
import com.offlinekeyboard.ime.layout.Layout
import com.offlinekeyboard.ime.layout.PopupEntry
import com.offlinekeyboard.ime.layout.Row

/**
 * The globe key's long-press menu: which languages it offers, and what choosing one does.
 *
 * iOS puts every enabled keyboard behind a hold on the globe and lets you slide to one directly,
 * rather than making you tap through them in a ring. Android has the same two facts available --
 * the enabled IMEs and each one's enabled subtypes -- but no equivalent affordance: the platform
 * offers `switchToNextInputMethod` (the ring) and a modal system dialog, and nothing in between.
 * This builds the in-between.
 *
 * ## Why this is not in the layout
 *
 * [com.offlinekeyboard.ime.layout.IosLayouts] declares keys that are the same on every device.
 * These entries are not: they are whatever the user has enabled, they differ per phone, and they
 * change while the keyboard is running -- a language added in Settings should appear in the menu
 * without a reinstall. So the layout declares a globe key with no languages and the service
 * injects the current ones every time it applies a layout, which is already every focus and every
 * subtype change.
 *
 * ## Ordering
 *
 * Our own subtypes go last, other keyboards first, and last means *nearest the thumb* -- the
 * column is built upward from the key. The common case is switching between the languages this
 * keyboard itself types, so those get the short travel; leaving for another app's keyboard is
 * rarer and is a bigger action, and it sits at the far end where it cannot be chosen by a
 * twitch. That is also iOS's arrangement, where the keyboards you use sit nearest and the
 * settings-like entries sit furthest.
 */
object LanguageMenu {

    /**
     * Separates the IME id from the subtype hash in a menu entry's [PopupEntry.Language.id].
     *
     * A vertical bar because an Android IME id is `package/.ServiceClass` and a subtype is
     * identified by a signed integer hash: slashes and minus signs are both already spoken for,
     * and neither ever appears as a bar.
     */
    private const val SEPARATOR = "|"

    /**
     * The menu's entries, in reading order: other keyboards at the top, ours at the bottom.
     *
     * Subtypes are asked for with `allowsImplicitlySelectedSubtypes` = true so a keyboard that
     * declares none still appears -- many third-party IMEs rely on the implicit subtype, and
     * asking only for explicit ones silently omits them from the menu entirely.
     *
     * Other keyboards are listed by *keyboard*, not by subtype: expanding another IME into all of
     * its languages produces a menu of thirty entries on a phone with two other keyboards
     * installed, and the subtype ids of another process are not reliably switchable anyway. Ours
     * is the only one expanded, because ours is the one this menu can actually land inside.
     */
    fun entries(context: Context): List<PopupEntry.Language> {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return emptyList()
        val self = context.packageName
        val currentSubtype = runCatching { imm.currentInputMethodSubtype }.getOrNull()

        val enabled = runCatching { imm.enabledInputMethodList }.getOrNull().orEmpty()

        val others = enabled
            .filter { it.packageName != self }
            .map { info ->
                PopupEntry.Language(
                    id = info.id,
                    label = info.loadLabel(context.packageManager)?.toString().orEmpty()
                        .ifBlank { info.packageName },
                    // Never current: if another keyboard were selected we would not be drawing.
                    current = false,
                )
            }

        val ours = enabled
            .firstOrNull { it.packageName == self }
            ?.let { info ->
                runCatching { imm.getEnabledInputMethodSubtypeList(info, true) }
                    .getOrNull()
                    .orEmpty()
                    .map { subtype ->
                        PopupEntry.Language(
                            id = info.id + SEPARATOR + subtype.hashCode(),
                            label = labelFor(context, info, subtype),
                            current = subtype.hashCode() == currentSubtype?.hashCode(),
                        )
                    }
            }
            .orEmpty()

        // A menu with one destination is not a menu. If nothing else is installed and we have a
        // single subtype, the hold has nothing to offer and the key stays tap-only.
        val all = others + ours
        return if (all.size < 2) emptyList() else all
    }

    /**
     * A subtype's display name, falling back through the ways Android has of not having one.
     *
     * `getDisplayName` is the correct call and returns the `android:label` from `method.xml`,
     * which is what our own subtypes set and what the system switcher shows. It returns empty for
     * subtypes that declare no label, so the locale's own name for itself is the fallback --
     * "Français" rather than "French", matching how every other language list on a phone reads.
     */
    private fun labelFor(
        context: Context,
        info: InputMethodInfo,
        subtype: InputMethodSubtype,
    ): String {
        val display = runCatching {
            subtype.getDisplayName(context, info.packageName, info.serviceInfo.applicationInfo)
                ?.toString()
        }.getOrNull().orEmpty()
        if (display.isNotBlank()) return display

        val tag = subtype.languageTag.ifEmpty { @Suppress("DEPRECATION") subtype.locale }
        if (tag.isBlank()) return info.loadLabel(context.packageManager)?.toString().orEmpty()
        val locale = java.util.Locale.forLanguageTag(tag.replace('_', '-'))
        return locale.getDisplayName(locale).ifBlank { tag }
    }

    /**
     * Attaches the current menu to whichever key in a layout is the globe.
     *
     * Returns the layout unchanged when there is nothing to offer, so a device with one language
     * and no other keyboards keeps a globe key whose hold does nothing -- rather than one that
     * opens a menu with a single entry that is already selected.
     */
    fun attach(layout: Layout, languages: List<PopupEntry.Language>): Layout {
        if (languages.isEmpty()) return layout
        if (layout.rows.none { row -> row.keys.any { it.type == KeyType.GLOBE } }) return layout
        return layout.copy(
            rows = layout.rows.map { row ->
                Row(
                    row.keys.map { key ->
                        if (key.type == KeyType.GLOBE) key.copy(languages = languages) else key
                    },
                )
            },
        )
    }

    /**
     * Switches to the destination a menu entry names.
     *
     * Two shapes of id, and they need different calls. One of ours carries a subtype hash and
     * switches *within* this keyboard, which keeps the IME the same and only changes the
     * language; another keyboard has no hash and replaces this IME wholesale.
     *
     * Both go through [InputMethodManager.setInputMethodAndSubtype] with the window token, which
     * is the form available to an IME switching *itself* away. The alternatives do not work here:
     * `setCurrentInputMethodSubtype` needs WRITE_SECURE_SETTINGS on modern Android, and
     * `InputMethodService.switchInputMethod(String)` cannot express a subtype.
     *
     * Returns false when the switch could not be made, so the caller can fall back to the ring.
     */
    fun switchTo(
        context: Context,
        token: IBinder?,
        id: String,
    ): Boolean {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return false
        val imeId = id.substringBefore(SEPARATOR)
        val subtypeHash = id.substringAfter(SEPARATOR, "").toIntOrNull()

        val subtype = subtypeHash?.let { hash ->
            val info = runCatching { imm.enabledInputMethodList }.getOrNull().orEmpty()
                .firstOrNull { it.id == imeId } ?: return false
            runCatching { imm.getEnabledInputMethodSubtypeList(info, true) }
                .getOrNull().orEmpty()
                .firstOrNull { it.hashCode() == hash } ?: return false
        }

        if (token == null) return false
        return runCatching {
            @Suppress("DEPRECATION")
            imm.setInputMethodAndSubtype(token, imeId, subtype)
            true
        }.getOrDefault(false)
    }

    /** True when this entry stays inside this keyboard rather than leaving for another IME. */
    fun isOwnSubtype(context: Context, id: String): Boolean =
        id.substringBefore(SEPARATOR).startsWith(context.packageName + "/")

    /** The IME half of a menu entry's id, which is all of it for another keyboard. */
    fun imeIdOf(id: String): String = id.substringBefore(SEPARATOR)

    /**
     * The [InputMethodSubtype] a menu entry names, when it is one of ours.
     *
     * Null for another keyboard, and null for one of ours that has since been disabled --
     * Settings can change underneath an open menu. The caller uses it to choose between the
     * subtype-switching API and the one that replaces the whole IME, so "not ours" and "gone"
     * both correctly mean "do not try to switch subtype".
     */
    fun ownSubtypeFor(context: Context, id: String): InputMethodSubtype? {
        if (!isOwnSubtype(context, id)) return null
        val hash = id.substringAfter(SEPARATOR, "").toIntOrNull() ?: return null
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return null
        val info = runCatching { imm.enabledInputMethodList }.getOrNull().orEmpty()
            .firstOrNull { it.id == imeIdOf(id) } ?: return null
        return runCatching { imm.getEnabledInputMethodSubtypeList(info, true) }
            .getOrNull().orEmpty()
            .firstOrNull { it.hashCode() == hash }
    }

    /** A globe key carrying the given languages, for tests and for layouts built by hand. */
    fun globeKey(languages: List<PopupEntry.Language>, widthUnits: Float = 1.25f): Key =
        Key("globe", "", languages = languages, widthUnits = widthUnits, type = KeyType.GLOBE)
}
