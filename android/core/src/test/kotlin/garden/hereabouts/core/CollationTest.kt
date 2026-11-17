// Checks that hereaboutsCompare puts strings in the website's order: collation.json lists every plant's common
// and Latin name plus crafted strings, sorted by the website's own sortPlants (JavaScript localeCompare).
package garden.hereabouts.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** A fixed shuffle (SplitMix64, the same as the iPhone app's test), so a failing order can be repeated. */
private class SplitMix(private var state: ULong) {
    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    /** Fisher-Yates. */
    fun <T> shuffled(list: List<T>): List<T> {
        val a = list.toMutableList()
        for (i in a.size - 1 downTo 1) {
            val j = (next() % (i + 1).toULong()).toInt()
            val t = a[i]
            a[i] = a[j]
            a[j] = t
        }
        return a
    }
}

class CollationTest {
    /** The website's sorted list from collation.json. */
    private fun websiteOrder(): List<String> {
        val cases = Expected.cases("collation.json")
        assertEquals(1, cases.size)
        return cases[0].strings("sorted")
    }

    @Test
    fun collationMatchesWebsite() {
        val want = websiteOrder()
        assertTrue(want.size > 300, "only ${want.size} strings")

        // Sorting a shuffled copy gives the website's order.
        val shuffled = SplitMix(20261116uL).shuffled(want)
        val got = shuffled.sortedWith { a, b -> hereaboutsCompare(a, b) }
        val moved = got.indices.filter { got[it] != want[it] }
        if (moved.isNotEmpty()) {
            val i = moved.first()
            println("collation.json: ${want.size} strings, ${moved.size} out of place after sorting")
            fail("${moved.size} of ${want.size} strings out of place; first at $i: expected \"${want[i]}\", got \"${got[i]}\"")
        }

        // Every pair compares the right way round, both ways: no two strings tie.
        val wrong = ArrayList<String>()
        var pairs = 0
        for (i in want.indices) {
            for (j in i + 1 until want.size) {
                pairs++
                val forward = hereaboutsCompare(want[i], want[j])
                val backward = hereaboutsCompare(want[j], want[i])
                if (forward >= 0 || backward <= 0) wrong.add("\"${want[i]}\" vs \"${want[j]}\": $forward, $backward")
            }
        }
        println("collation.json: ${want.size} strings, $pairs pairs compared both ways, ${moved.size} out of place, ${wrong.size} pairs wrong")
        if (wrong.isNotEmpty()) fail("${wrong.size} of $pairs pairs compare the wrong way; first: " + wrong.take(5).joinToString("; "))
    }

    @Test
    fun tableIsTheSharedList() {
        // collation.json's table is scripts/app/collation-table.mjs, the list the bundle check uses to refuse plant
        // names this comparator cannot sort. Collation.table must hold exactly the same characters and weights.
        val rows = Expected.field("collation.json", "table") as? JsonArray ?: fail("collation.json's table is not a list")
        val want = rows.map { row ->
            val r = row as? JsonArray ?: fail("a table row is not a list: $row")
            r.map { x ->
                val d = (x as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: fail("a table row has a non-number: $row")
                if (d < 0 || d != Math.floor(d) || d > Int.MAX_VALUE) fail("a table row has a number that is not a small whole number: $row")
                d.toInt()
            }
        }
        val got = Collation.table.keys.sorted().map { cp ->
            val w = Collation.table.getValue(cp)
            listOf(cp, w.primary, w.secondary, w.tertiary)
        }
        assertTrue(want.size > 130, "only ${want.size} rows")
        assertEquals(want.size, got.size)
        val differ = got.indices.filter { got[it] != want[it] }
        if (differ.isNotEmpty()) {
            val i = differ.first()
            fail("${differ.size} rows differ; first: Kotlin ${got[i]}, shared list ${want[i]}")
        }
    }

    @Test
    fun readableExamples() {
        // Spaces, then digits one at a time, then letters; accents after plain letters; lower case before upper case.
        val order = listOf(" apple", "1 apple", "10 apples", "2 apples", "a1", "a10", "a2", "A2", "apple", "Apple", "äpple")
        for ((a, b) in order.zipWithNext()) {
            assertTrue(hereaboutsCompare(a, b) < 0, "$a < $b")
            assertTrue(hereaboutsCompare(b, a) > 0, "$b > $a")
        }
        assertEquals(0, hereaboutsCompare("Jack in the Pulpit", "Jack in the Pulpit"))
        // A precomposed letter and the same letter written as letter plus accent are the same string to ICU.
        assertEquals(0, hereaboutsCompare("éclair", "éclair"))
        // cote < coté < côte < côté: the first accent difference decides.
        val accents = listOf("cote", "coté", "côte", "côté")
        for ((a, b) in accents.zipWithNext()) assertTrue(hereaboutsCompare(a, b) < 0, "$a < $b")
    }
}
