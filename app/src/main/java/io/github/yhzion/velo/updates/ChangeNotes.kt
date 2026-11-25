package io.github.yhzion.velo.updates

/** The Keep a Changelog sections the app shows, in the order it shows them, with their titles. */
enum class ChangeKind(val title: String) {
    Added("New"), Changed("Changed"), Fixed("Fixed"), Removed("Removed"), Security("Security"),
}

data class ChangeSection(val kind: ChangeKind, val items: List<String>)

private const val CHANGES_START = "<!-- velo-changes -->"
private const val CHANGES_END = "<!-- /velo-changes -->"
internal const val MAX_CHANGE_ITEMS = 12
internal const val MAX_CHANGE_LENGTH = 200
private val sectionHeading = Regex("""### +(\S+) *""")
// [text](url), or an image's ![alt](url), with one level of parentheses inside the url.
private val link = Regex("""!?\[([^\]]*)]\((?:[^()\s]|\([^()\s]*\))*\)""")

/**
 * The change notes the release workflow puts in a release body: the `### Section` and `- item`
 * lines between the [CHANGES_START] and [CHANGES_END] lines. Anything else is ignored, and a body
 * without a closed block has none. Items are plain text: links keep only their text. Sections
 * come in [ChangeKind] order, with at most [MAX_CHANGE_ITEMS] items in all, each cut to
 * [MAX_CHANGE_LENGTH] characters.
 */
internal fun changeNotes(body: String): List<ChangeSection> {
    val lines = body.lines().map { it.trim() }
    val start = lines.indexOf(CHANGES_START)
    if (start < 0) return emptyList()
    val end = lines.subList(start + 1, lines.size).indexOf(CHANGES_END)
    if (end < 0) return emptyList()
    val found = ChangeKind.entries.associateWith { mutableListOf<String>() }
    var section: ChangeKind? = null
    // Lines are trimmed for the markers only: an indented dash is a nested item, not one of ours.
    for (line in body.lines().subList(start + 1, start + 1 + end)) {
        val heading = sectionHeading.matchEntire(line.trimEnd())
        if (heading != null) {
            section = ChangeKind.entries.firstOrNull { it.name == heading.groupValues[1] }
            continue
        }
        if (!line.startsWith("- ")) continue
        val item = link.replace(line.substring(2)) { it.groupValues[1] }.trim()
        if (item.isNotEmpty()) section?.let { found.getValue(it) += item }
    }
    var left = MAX_CHANGE_ITEMS
    return ChangeKind.entries.mapNotNull { kind ->
        val items = found.getValue(kind).take(left).map(::truncateItem)
        left -= items.size
        items.takeIf { it.isNotEmpty() }?.let { ChangeSection(kind, it) }
    }
}

private fun truncateItem(item: String): String {
    if (item.length <= MAX_CHANGE_LENGTH) return item
    var cut = MAX_CHANGE_LENGTH - 1
    if (item[cut - 1].isHighSurrogate()) cut--
    return item.substring(0, cut) + "…"
}
