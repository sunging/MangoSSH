package website.sung.mangossh.domain

/**
 * Merges the persisted [current] value into a settings picker's discrete
 * [choices].
 *
 * The preference stores accept any in-range value, so a value the picker never
 * offers can legitimately be persisted: a 45-second keepalive written by a
 * newer build, restored from a backup, or set by hand. Offering only the
 * discrete list would leave the dialog with no option selected and put the
 * stored value one stray tap away from being lost, so an in-range [current] is
 * inserted in sorted order instead. Values [allowed] rejects are left out: the
 * owning model's `normalized()` replaces them with its default anyway.
 */
internal fun <T : Comparable<T>> choicesIncludingCurrent(
    choices: List<T>,
    current: T,
    allowed: (T) -> Boolean,
): List<T> = if (current in choices || !allowed(current)) choices else (choices + current).sorted()
