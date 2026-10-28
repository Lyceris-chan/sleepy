package dev.sleepy.app.ui.state

/**
 * Filters the patch list by what a row would change.
 *
 * A section with dozens of rows is hard to scan, and the rows a person wants are often named
 * after the thing being changed rather than the section that files it. The search matches the
 * words a row already carries - its label, description and heading - plus the section's own name
 * and description, so "friends", "calls" or "boosts" find the row without the reader knowing
 * which section it lives in.
 *
 * Every term has to match, so a second word narrows a result rather than widening it. Terms are
 * case-insensitive substrings, which keeps "call" and "calls" together without a stemmer.
 */
object PatchSearch {

    private val whitespace = Regex("\\s+")

    /** A matching section and the rows that matched in it, still grouped by their heading. */
    data class Section(
        val label: String,
        val description: String,
        val groups: List<PatchRowGroup>
    ) {
        val rows: List<PatchRow> get() = groups.flatMap { it.rows }
    }

    /**
     * What a query matched.
     *
     * @property sections Matching patch sections, in list order.
     * @property permissions Matching permission rows, in declaration order.
     * @property total Rows across [sections] plus [permissions].
     */
    data class Results(
        val sections: List<Section>,
        val permissions: List<PatchRow>,
        val total: Int
    )

    /** The terms of [query], lowercased and without empty parts. */
    fun terms(query: String): List<String> =
        query.trim().lowercase().split(whitespace).filter { it.isNotEmpty() }

    /**
     * True when every term of [query] appears in the row or the section that holds it.
     *
     * A blank query matches everything, which is what makes the unfiltered list the same
     * function rather than a separate path.
     */
    fun matches(
        row: PatchRow,
        sectionLabel: String,
        sectionDescription: String,
        query: String
    ): Boolean {
        val terms = terms(query)
        if (terms.isEmpty()) return true
        val text = buildString {
            append(row.label)
            append(' ')
            append(row.description)
            append(' ')
            append(row.group)
            append(' ')
            append(sectionLabel)
            append(' ')
            append(sectionDescription)
        }.lowercase()
        return terms.all { text.contains(it) }
    }

    /**
     * The rows named by [query], or everything when it is blank.
     *
     * Sections with no matching row are absent rather than empty, so the result list has no
     * headings with nothing under them.
     */
    fun search(
        sections: List<PatchSectionRows>,
        permissions: List<PatchRow>,
        query: String
    ): Results {
        if (query.isBlank()) {
            return Results(
                sections = sections.map { Section(it.label, it.description, it.groups) },
                permissions = permissions,
                total = sections.sumOf { it.rows.size } + permissions.size
            )
        }

        val matchingSections = sections.mapNotNull { section ->
            val rows = section.rows.filter { row ->
                matches(row, section.label, section.description, query)
            }
            if (rows.isEmpty()) {
                null
            } else {
                Section(section.label, section.description, groupsOf(rows))
            }
        }
        val matchingPermissions = permissions.filter { row ->
            matches(row, "Permissions", "Permissions declared by the build", query)
        }
        return Results(
            sections = matchingSections,
            permissions = matchingPermissions,
            total = matchingSections.sumOf { it.rows.size } + matchingPermissions.size
        )
    }

    /** The rows collected under their headings, in the order each heading first appears. */
    private fun groupsOf(rows: List<PatchRow>): List<PatchRowGroup> {
        val byGroup = LinkedHashMap<String, MutableList<PatchRow>>()
        rows.forEach { row -> byGroup.getOrPut(row.group) { mutableListOf() } += row }
        return byGroup.map { (label, groupRows) -> PatchRowGroup(label, groupRows) }
    }
}
