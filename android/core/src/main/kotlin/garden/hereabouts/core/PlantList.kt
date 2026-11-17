// The plant list: search, filters and the ten sorts, ported line by line from the website's index.html
// (setOverlap, normalizeName, nameMatches, passes, CAT_ORDER and sortPlants, and hiddenNameMatches). The answers
// must match the website's exactly; the tests check every case in apps/shared/bundle/expected/list.json.
//
// The page builds its list as render() does: plants.filter { passes } -> sorted -> and, only when the search box is
// not empty (spaces count as not empty), hidden() for the "Also matching" lines.
package garden.hereabouts.core

/** The sort menu, in the website's order. `key` is the website's option value; `label` is the words in its menu. */
enum class SortKey(val key: String, val label: String) {
    Suggested("suggested", "Suggested"),
    SellersDesc("sellers-desc", "Most sellers"),
    CommonAsc("common-asc", "Common name (A to Z)"),
    CommonDesc("common-desc", "Common name (Z to A)"),
    SciAsc("sci-asc", "Scientific name (A to Z)"),
    HeightAsc("height-asc", "Height (low to high)"),
    HeightDesc("height-desc", "Height (high to low)"),
    BloomAsc("bloom-asc", "Bloom (earliest first)"),
    Category("category", "Plant type"),
    WildlifeDesc("wildlife-desc", "Wildlife value"),
    ;

    companion object {
        /** The sort with this option value, or null for a value the menu does not have. */
        fun fromKey(key: String): SortKey? = entries.firstOrNull { it.key == key }
    }
}

/** The "Where to buy" filter: a shop within 75 miles, or a seller that ships here. */
enum class BuyMode(val key: String) {
    Pickup("pickup"),
    Ship("ship"),
    ;

    companion object {
        /** The mode with this value, or null for a value the website does not have. */
        fun fromKey(key: String): BuyMode? = entries.firstOrNull { it.key == key }
    }
}

/** Everything the visitor has chosen: the website's `state` object. An empty set means "no limit". */
data class FilterState(
    val sun: Set<String> = emptySet(),
    val moisture: Set<String> = emptySet(),
    val category: Set<String> = emptySet(),
    val wildlife: Set<String> = emptySet(),
    val zones: Set<String> = emptySet(),
    val bloomMonth: Set<Int> = emptySet(),
    val bloomColor: Set<String> = emptySet(),
    /** Feet. A plant shows when its smallest height is at most this. */
    val maxHeight: Double = 100.0,
    val buy: Set<BuyMode> = emptySet(),
    /** What is typed in the search box, untrimmed. Only the empty string counts as no search. */
    val search: String = "",
    val sort: SortKey = SortKey.Suggested,
    val location: Location? = null,
)

/** hiddenNameMatches' answer: plants that match the search but fail only the county check, and those hidden by a filter. */
data class HiddenMatches(val county: List<Plant>, val filters: List<Plant>)

/** How many plants there are of one type: one type chip. */
data class TypeCount(val category: String, val count: Int)

object PlantList {
    /** CAT_ORDER: the plant types in the website's order. */
    val categoryOrder: List<String> = listOf("tree", "shrub", "perennial", "grass", "fern", "vine", "groundcover")

    /** setOverlap: true when nothing is chosen, or the plant has at least one chosen value. */
    private fun <T> overlaps(values: List<T>, chosen: Set<T>): Boolean = chosen.isEmpty() || values.any { it in chosen }

    /**
     * normalizeName: lower case, accents removed, apostrophes dropped, hyphens and dashes read as spaces, runs of
     * spaces collapsed, trimmed. So "Culver’s" is "culvers" and "Jack–in–the–Pulpit" is "jack in the pulpit".
     */
    fun normalizeName(s: String): String {
        // .normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
        val lower = JsText.unaccentedLower(s)
        // .replace(/['’‘`]/g, '').replace(/[-‐-―]/g, ' ')
        val cleaned = StringBuilder(lower.length)
        for (c in lower) {
            when {
                c == '\'' || c == '’' || c == '‘' || c == '`' -> {}
                c == '-' || c in '‐'..'―' -> cleaned.append(' ')
                else -> cleaned.append(c)
            }
        }
        // .replace(/\s+/g, ' ').trim()
        return JsText.trimmed(JsText.collapsedSpaces(cleaned))
    }

    /** nameMatches: true when the whole search, or every word of it, appears in the common or Latin name. */
    fun nameMatches(p: Plant, query: String): Boolean {
        val q = normalizeName(query)
        if (q.isEmpty()) return true
        // String.contains compares UTF-16 units, as JavaScript's includes does.
        val names = listOf(normalizeName(p.common), normalizeName(p.sci))
        if (names.any { it.contains(q) }) return true
        // q.split(' '): q is trimmed with single spaces, so no word is empty.
        return q.split(' ').all { w -> names.any { it.contains(w) } }
    }

    /** passes: true when the plant shows in the list for these choices. */
    fun passes(p: Plant, st: FilterState, data: AppData): Boolean {
        if (st.search.isNotEmpty() && !nameMatches(p, st.search)) return false
        if (!overlaps(p.sun, st.sun)) return false
        if (!overlaps(p.moisture, st.moisture)) return false
        if (st.category.isNotEmpty() && p.category !in st.category) return false
        if (!overlaps(p.wildlife, st.wildlife)) return false
        if (!overlaps(p.zones, st.zones)) return false
        if (!overlaps(p.bloomMonths, st.bloomMonth)) return false
        if (!overlaps(p.bloomColor, st.bloomColor)) return false
        // p.height[0] > st.maxHeight (a missing height compares as false in JavaScript, so it passes)
        val smallest = p.height.firstOrNull()
        if (smallest != null && smallest > st.maxHeight) return false
        val loc = st.location
        if (st.buy.isNotEmpty() && loc != null) {
            val g = Sellers.group(p, loc, data.vendors)
            val ok = (BuyMode.Pickup in st.buy && g.nearby.isNotEmpty()) || (BuyMode.Ship in st.buy && g.ships.isNotEmpty())
            if (!ok) return false
        }
        if (loc != null && !LocationLookup.nativeHere(p.id, loc, data.geo)) return false
        return true
    }

    /**
     * The larger height (a.height[1] on the website), or null when the plant does not have two finite heights.
     *
     * For such a plant the website's order is not defined: a.height[1] is undefined, the subtraction gives NaN, and
     * the sort treats the plant as equal to every other, which is not a consistent order. Kotlin's sortedWith can
     * throw on an inconsistent comparison, so the app uses its own rule, which is ours and not the website's: plants
     * without two heights go after all the others, in data order, in both height sorts. The iPhone app does the same.
     */
    private fun tallest(p: Plant): Double? =
        if (p.height.size > 1 && p.height[0].isFinite() && p.height[1].isFinite()) p.height[1] else null

    /**
     * The height sorts: plants with two heights by their larger height (low to high, or high to low when
     * `descending`), then the plants without, which this comparison calls equal, so the stable sort keeps their
     * data order.
     */
    private fun byHeight(descending: Boolean) = Comparator<Plant> { a, b ->
        val ta = tallest(a)
        val tb = tallest(b)
        if (ta == null || tb == null) {
            // A plant without heights goes after one with heights; two without are equal.
            (if (ta == null) 1 else 0) - (if (tb == null) 1 else 0)
        } else if (descending) {
            JsText.sign(tb - ta)
        } else {
            JsText.sign(ta - tb)
        }
    }

    /** a.bloomMonths[0] || 99: the first bloom month, or 99 when there is none. */
    private fun firstBloom(p: Plant): Int {
        val m = p.bloomMonths.firstOrNull() ?: 0
        return if (m == 0) 99 else m
    }

    /** CAT_ORDER.indexOf(category): -1 for a type not in the list. */
    private fun categoryIndex(p: Plant): Int = categoryOrder.indexOf(p.category)

    /**
     * sortPlants, with the sort and location from `st`. Stable, like JavaScript's Array.prototype.sort: plants the
     * sort calls equal keep their order (Kotlin's sortedWith is a stable sort).
     */
    fun sorted(plants: List<Plant>, st: FilterState, data: AppData): List<Plant> {
        val byCommon = Comparator<Plant> { a, b -> hereaboutsCompare(a.common, b.common) }
        return when (st.sort) {
            SortKey.Suggested -> {
                // Plants a seller that pays us can sell to this visitor come first, then the "Most sellers" order
                // inside each group, then by common name.
                class Keyed(val plant: Plant, val aff: Int, val sellers: Int)
                plants.map { p ->
                    val all = Sellers.group(p, st.location, data.vendors).all
                    Keyed(p, if (all.any { it.vendor.affiliate == true }) 1 else 0, all.size)
                }.sortedWith { a, b ->
                    if (b.aff != a.aff) b.aff - a.aff
                    else if (b.sellers != a.sellers) b.sellers - a.sellers
                    else byCommon.compare(a.plant, b.plant)
                }.map { it.plant }
            }
            SortKey.SellersDesc -> {
                // The same count the card shows: sellers that can sell to this visitor.
                plants.map { it to Sellers.group(it, st.location, data.vendors).all.size }
                    .sortedWith { a, b -> if (b.second != a.second) b.second - a.second else byCommon.compare(a.first, b.first) }
                    .map { it.first }
            }
            SortKey.CommonAsc -> plants.sortedWith(byCommon)
            SortKey.CommonDesc -> plants.sortedWith { a, b -> byCommon.compare(b, a) }
            SortKey.SciAsc -> plants.sortedWith { a, b -> hereaboutsCompare(a.sci, b.sci) }
            SortKey.HeightAsc -> plants.sortedWith(byHeight(descending = false))
            SortKey.HeightDesc -> plants.sortedWith(byHeight(descending = true))
            SortKey.BloomAsc -> plants.sortedWith { a, b -> firstBloom(a) - firstBloom(b) }
            SortKey.Category -> plants.sortedWith { a, b ->
                val d = categoryIndex(a) - categoryIndex(b)
                if (d != 0) d else byCommon.compare(a, b)
            }
            SortKey.WildlifeDesc -> plants.sortedWith { a, b -> b.wildlife.size - a.wildlife.size }
        }
    }

    /**
     * hiddenNameMatches: plants whose name matches the search but are not in the list, and why. A plant that fails
     * only the county check goes under `county` ("Also matching, but not native to ..."); one that fails any other
     * filter goes under `filters` ("Also matching, but hidden by your filters"). Each is sorted by common name. The
     * website asks for these only when the search box is not empty.
     */
    fun hidden(shown: List<Plant>, st: FilterState, data: AppData): HiddenMatches {
        val shownIds = shown.map { it.id }.toSet()
        val county = ArrayList<Plant>()
        val filters = ArrayList<Plant>()
        val withoutLocation = st.copy(location = null)
        for (p in data.plants) {
            if (p.id in shownIds || !nameMatches(p, st.search)) continue
            if (st.location != null && passes(p, withoutLocation, data)) county.add(p) else filters.add(p)
        }
        val byName = Comparator<Plant> { a, b -> hereaboutsCompare(a.common, b.common) }
        return HiddenMatches(county.sortedWith(byName), filters.sortedWith(byName))
    }

    /** How many of the plants are of each type, in CAT_ORDER, leaving out types with none: the type chips. */
    fun typeCounts(plants: List<Plant>): List<TypeCount> {
        val counts = plants.groupingBy { it.category }.eachCount()
        return categoryOrder.mapNotNull { c -> counts[c]?.takeIf { it > 0 }?.let { TypeCount(c, it) } }
    }
}
