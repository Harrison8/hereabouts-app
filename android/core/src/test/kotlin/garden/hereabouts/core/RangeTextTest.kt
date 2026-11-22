// The ranges the app shows, checked without the screen: a range written with an en dash or a hyphen reads with "to"
// ("6 to 12 ft", "Mar to Apr", "$3.25 to $6.50"), and words that only look like ranges stay as the data writes them
// (a size like "3-gallon", a hyphenated word, a negative number, a date, a source's catalogue number). Every case
// below is a string the bundled plants, sellers or garden jobs really hold, or one the website's labels make from them.
// The same cases as the iPhone app's.
package garden.hereabouts.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RangeTextTest {
    private fun check(data: String, shown: String) {
        assertEquals(shown, RangeText.display(data), "\"$data\"")
    }

    @Test
    fun anEnDashRangeReadsTo() {
        check("6–12 ft", "6 to 12 ft")
        check("1.5–2.5 ft", "1.5 to 2.5 ft")
        check("Mar–Apr", "Mar to Apr")
        check("Mar–May, Jul", "Mar to May, Jul")
        check("2–3 years to full size", "2 to 3 years to full size")
        check(
            "Slow-moderate (about 1–1.5 ft a year); 6–12 ft in about 6–8 years; fruit in 3–6 years",
            "Slow to moderate (about 1 to 1.5 ft a year); 6 to 12 ft in about 6 to 8 years; fruit in 3 to 6 years",
        )
        check(
            "Any time between your first fall frost and your last spring frost, cut dead stems to 12–24 in.",
            "Any time between your first fall frost and your last spring frost, cut dead stems to 12 to 24 in.",
        )
        check("\$16.95–\$35.95", "\$16.95 to \$35.95")
        check("berries by year 2nd–3rd", "berries by year 2nd to 3rd")
    }

    @Test
    fun twoGrowthRatesReadToWhicheverDashJoinsThem() {
        // The data writes the same growth rate both ways; the screen reads them the same.
        check("Slow–moderate", "Slow to moderate")
        check("Slow-moderate", "Slow to moderate")
        check(
            "Moderate–rapid; a 2-in mat spreading 18 in.+; fills in 2–3 years",
            "Moderate to rapid; a 2-in mat spreading 18 in.+; fills in 2 to 3 years",
        )
        check("Moderate–fast", "Moderate to fast")
        check("Medium-fast from plug", "Medium to fast from plug")
        check(
            "Medium-slow; spreads to a 1.5–2 ft colony over 3–5 years",
            "Medium to slow; spreads to a 1.5 to 2 ft colony over 3 to 5 years",
        )
    }

    @Test
    fun anEnDashBetweenOtherWordsStays() {
        // A plant's name from the website's list, and the days in a seller's opening hours.
        check("Jack–in–the–Pulpit", "Jack–in–the–Pulpit")
        check("Mon–Sat", "Mon–Sat")
    }

    @Test
    fun aHyphenRangeReadsTo() {
        check("1.5-2.5 ft", "1.5 to 2.5 ft")
        check("on rigid 3-5 ft stalks", "on rigid 3 to 5 ft stalks")
        check("#3 Container | 1-2 ft", "#3 Container | 1 to 2 ft")
        check("1-3 gal", "1 to 3 gal")
        check("\$3.25-\$6.50", "\$3.25 to \$6.50")
        check("(~1-1.5 ft/yr)", "(~1 to 1.5 ft/yr)")
        check("12-18\" tall", "12 to 18\" tall")
        check("about 20-25% of", "about 20 to 25% of")
        check("spidery yellow flowers Oct-Nov", "spidery yellow flowers Oct to Nov")
        check("blue bell clusters in April-May", "blue bell clusters in April to May")
        check("Blooms Aug-Oct, year 2", "Blooms Aug to Oct, year 2")
    }

    @Test
    fun aValueWithNoRangeIsUnchanged() {
        check("", "")
        check("Shrub", "Shrub")
        check("6 ft", "6 ft")
        check("Full sun, part shade", "Full sun, part shade")
        check("Mar, May, Jul", "Mar, May, Jul")
    }

    @Test
    fun wordsThatOnlyLookLikeRangesAreUnchanged() {
        // Hyphenated words and sizes.
        check("Slow-spreading", "Slow-spreading")
        check("fast-growing", "fast-growing")
        check("Moderate-to-fast grower", "Moderate-to-fast grower")
        check("3-gallon", "3-gallon")
        check("4-inch pot", "4-inch pot")
        check("1-Pack", "1-Pack")
        check("1- and 3-gallon", "1- and 3-gallon")
        check("Plant - 5\" Deep Pot", "Plant - 5\" Deep Pot")
        check("frost-and-fog–covered", "frost-and-fog–covered")
        // A sentence dash with a space on each side.
        check("one – two", "one – two")
        // A negative number, a date, a fraction and a source's catalogue number.
        check("-5", "-5")
        check("down to -20 F", "down to -20 F")
        check("2026-11-20", "2026-11-20")
        check("NA-FR-01-95", "NA-FR-01-95")
        check("3/4-1 in", "3/4-1 in")
    }

    @Test
    fun theWebsitesLabelsKeepTheirEnDashAndTheScreenReadsTo() {
        // Labels words a range as the website does, with an en dash; only what the screen shows reads "to".
        assertEquals("6–12 ft", Labels.ftRange(listOf(6.0, 12.0)))
        assertEquals("6 to 12 ft", RangeText.display(Labels.ftRange(listOf(6.0, 12.0))))
        assertEquals("1.5 to 2.5 ft", RangeText.display(Labels.ftRange(listOf(1.5, 2.5))))
        assertEquals("Mar to Apr", RangeText.display(Labels.bloomRange(listOf(3, 4)) ?: ""))
        assertEquals(
            "Slow to moderate (about 1 to 1.5 ft a year); 6 to 12 ft in about 6 to 8 years; fruit in 3 to 6 years",
            RangeText.display(Labels.growth("Slow-moderate (~1-1.5 ft/yr); 6-12 ft in ~6-8 yrs; fruit in 3-6 yrs") ?: ""),
        )
    }
}
