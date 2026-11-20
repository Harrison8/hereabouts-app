// The plant page's "Native range" map: the lower 48 states' ecoregions as outlines, and the visitor's place as a
// dot. A port of the website's range-shapes.js, read from the bundle's tables/range-shapes.json, which
// the website's range-map logic writes from the same numbers. The same as the iPhone app's RangeMap.swift. The tests
// check it against expected/map.json.
package garden.hereabouts.core

import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The map's size, its projection and the outline of each ecoregion. */
@Serializable
data class RangeShapes(
    val width: Double,
    val height: Double,
    val projection: Projection,
    /** An SVG path for each ecoregion code, like "M14 173L13 180Z". */
    val regions: Map<String, String>,
) {
    /**
     * The numbers of the map's projection (an Albers equal-area conic projection centred on longitude `lon0`).
     * `x0` and `y0` already include the map's 4-point margin, so `point` must not add it again.
     */
    @Serializable
    data class Projection(
        val n: Double,
        val C: Double,
        val r0: Double,
        val x0: Double,
        val y0: Double,
        val scale: Double,
        val lon0: Double,
    )

    /** A region's outline used a drawing command other than M (move), L (line) and Z (close), or was cut short. */
    class PathError(message: String) : Exception(message)

    /** A longitude and latitude as (x, y) on the map, as RANGE_SHAPES.point does. */
    fun point(lon: Double, lat: Double): Pair<Double, Double> {
        val p = projection
        val rad = Math.PI / 180
        val r = sqrt(p.C - 2 * p.n * sin(lat * rad)) / p.n
        val t = p.n * (lon - p.lon0) * rad
        return (r * sin(t) - p.x0) * p.scale to (p.y0 - (p.r0 - r * cos(t))) * p.scale
    }

    /**
     * The outline of one ecoregion as a list of closed shapes, each a list of (x, y) points. An ecoregion the map
     * does not have gives an empty list. Throws PathError when the outline uses any command but M, L and Z.
     */
    fun paths(region: String): List<List<Pair<Double, Double>>> {
        val d = regions[region] ?: return emptyList()
        return parse(d)
    }

    private sealed interface Token {
        data class Command(val c: Char) : Token
        data class Number(val n: Double) : Token
    }

    companion object {
        /** Reads range-shapes.json. */
        fun load(file: File): RangeShapes = json.decodeFromString(serializer(), file.readText(Charsets.UTF_8))

        /**
         * Splits a path into commands and numbers. Numbers are written like "12", "-3.5" or "1e-3"; spaces and commas
         * separate them.
         */
        private fun tokens(s: String): List<Token> {
            val out = ArrayList<Token>()
            var i = 0
            fun isDigit(k: Int) = k < s.length && s[k] in '0'..'9'
            while (i < s.length) {
                val u = s[i]
                if (u == ' ' || u == ',' || u == '\n' || u == '\t' || u == '\r') {
                    i++
                } else if (u in 'A'..'Z' || u in 'a'..'z') {
                    out.add(Token.Command(u))
                    i++
                } else if (u == '-' || u == '+' || u == '.' || isDigit(i)) {
                    val start = i
                    if (u == '-' || u == '+') i++
                    while (isDigit(i)) i++
                    if (i < s.length && s[i] == '.') {
                        i++
                        while (isDigit(i)) i++
                    }
                    if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                        var j = i + 1
                        if (j < s.length && (s[j] == '-' || s[j] == '+')) j++
                        if (isDigit(j)) {
                            i = j
                            while (isDigit(i)) i++
                        }
                    }
                    val text = s.substring(start, i)
                    // The text is only a sign, digits, one point and an exponent, so toDoubleOrNull reads it as
                    // JavaScript would; a sign or a point on its own does not read.
                    val n = text.toDoubleOrNull() ?: throw PathError("bad number \"$text\"")
                    out.add(Token.Number(n))
                } else {
                    throw PathError("unexpected character \"$u\"")
                }
            }
            return out
        }

        /**
         * Reads M, L and Z commands into closed shapes. M starts a shape, L adds points to it, and further number
         * pairs after either add points too. Z closes the shape; only M may follow it.
         */
        internal fun parse(d: String): List<List<Pair<Double, Double>>> {
            val list = tokens(d)
            val shapes = ArrayList<List<Pair<Double, Double>>>()
            var current: ArrayList<Pair<Double, Double>>? = null
            var command: Char? = null
            var i = 0
            while (i < list.size) {
                when (val token = list[i]) {
                    is Token.Command -> {
                        i++
                        when (token.c) {
                            'M' -> {
                                current?.let { shapes.add(it) }
                                current = ArrayList()
                                command = token.c
                            }
                            'L' -> {
                                if (current == null) throw PathError("L with no shape started")
                                command = token.c
                            }
                            'Z' -> {
                                val open = current ?: throw PathError("Z with no shape started")
                                shapes.add(open)
                                current = null
                                command = null
                            }
                            else -> throw PathError("unsupported command \"${token.c}\"")
                        }
                    }
                    is Token.Number -> {
                        val open = current
                        if (command == null || open == null) throw PathError("a number with no command")
                        val y = (list.getOrNull(i + 1) as? Token.Number) ?: throw PathError("a point with no y")
                        open.add(token.n to y.n)
                        i += 2
                    }
                }
            }
            current?.let { shapes.add(it) }
            return shapes
        }
    }
}
