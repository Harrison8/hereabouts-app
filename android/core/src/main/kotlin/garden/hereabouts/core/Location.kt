// Location finding, ported line by line from the website's location.js: turns what a visitor types (a ZIP, a city
// or county name, a state) or where the phone is into a county, then into an EPA ecoregion, and answers "is this
// plant native here". The answers must match the website's exactly; the tests check every case in
// apps/shared/bundle/expected/location.json. The iPhone app's Location.swift is the same port in Swift.
//
// The website's regular expressions are written out here as plain code over UTF-16 units, which is what they work
// on in JavaScript (none of them has the u flag except displayName's, which works on code points here too). Each
// function says which JavaScript lines it follows.
package garden.hereabouts.core

/** What a visitor typed, as classifyInput in location.js sorts it. */
sealed interface InputKind {
    data object Empty : InputKind

    /** Five digits, from a ZIP, a ZIP+4, or a ZIP at the end of "Dayton, OH 45402". */
    data class Zip(val zip: String) : InputKind

    /** Starts with a house number. The text is trimmed. */
    data class Address(val text: String) : InputKind

    /** Only a state, like "Ohio" or "state of ohio". Never DC, which is looked up like a city. */
    data class State(val code: String, val name: String) : InputKind

    /** Anything else: a city or county name. The text is trimmed. */
    data class Place(val text: String) : InputKind
}

/** A county found from a ZIP, a position or a county code. */
sealed interface LocationResult {
    data class Found(val location: Location) : LocationResult

    /** The county is outside the lower 48 states and DC, or has no ecoregion (the website's "outside_us48"). */
    data object OutsideUs48 : LocationResult

    /**
     * The ZIP is not in the ZIP table (the website returns null and says "We couldn't find ZIP ..."), or, for
     * `nearest`, the ZIP table is empty.
     */
    data object NotFound : LocationResult

    /** The website's word for the problem, or null when a county was found. */
    val error: String?
        get() = when (this) {
            is Found -> null
            OutsideUs48 -> "outside_us48"
            NotFound -> "not_found"
        }
}

/**
 * Whether a typed name matched a city or a county (lookupPlace's `kind`). The website sends it as the `method` of its
 * location_set and geocode_failed events.
 */
enum class PlaceKind(val word: String) {
    City("city"),
    County("county"),
}

/** A county found from a typed city or county name. */
sealed interface PlaceResult {
    data class Found(val location: Location, override val kind: PlaceKind) : PlaceResult

    /** Several states have a place with this name; `states` lists them, biggest place first (the website's "ambiguous"). */
    data class Ambiguous(val states: List<String>) : PlaceResult {
        override val kind: PlaceKind? get() = null
    }

    /**
     * The website's "outside_us48". `kind` is null when the name was only found in a state outside the lower 48
     * (lookupPlace's own error, which the website reports as method "city"); it is set when lookupPlace found one
     * place but its county has no ecoregion or is outside the lower 48 (the website reports that place's kind).
     */
    data class OutsideUs48(override val kind: PlaceKind?) : PlaceResult

    /** No place has this name (the website's "not_found"). */
    data object NotFound : PlaceResult {
        override val kind: PlaceKind? get() = null
    }

    /** lookupPlace's kind, when it found a single place (even one outside the lower 48), else null. */
    val kind: PlaceKind?

    /** The website's word for the problem, or null when a county was found. */
    val error: String?
        get() = when (this) {
            is Found -> null
            is Ambiguous -> "ambiguous"
            is OutsideUs48 -> "outside_us48"
            NotFound -> "not_found"
        }
}

/** A US state or territory, as US_STATES in location.js lists them. */
data class UsState(val code: String, val name: String)

object LocationLookup {
    /** US_STATES in location.js, in its order: every state and territory, so a typed state is recognized even where there are no plants. */
    val usStates: List<UsState> = listOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas", "CA" to "California",
        "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware", "DC" to "District of Columbia",
        "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho", "IL" to "Illinois",
        "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas", "KY" to "Kentucky", "LA" to "Louisiana",
        "ME" to "Maine", "MD" to "Maryland", "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota",
        "MS" to "Mississippi", "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
        "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York",
        "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma", "OR" to "Oregon",
        "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina", "SD" to "South Dakota",
        "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah", "VT" to "Vermont", "VA" to "Virginia",
        "WA" to "Washington", "WV" to "West Virginia", "WI" to "Wisconsin", "WY" to "Wyoming",
        "PR" to "Puerto Rico", "GU" to "Guam", "VI" to "US Virgin Islands", "AS" to "American Samoa",
        "MP" to "Northern Mariana Islands",
    ).map { UsState(it.first, it.second) }

    /** States that sit in a single ecoregion (ONE_ECOREGION_STATES). */
    internal val oneEcoregionStates: Set<String> = setOf("DE", "RI")

    /** States and territories outside the lower 48 (OUTSIDE_US48_STATES). */
    internal val outsideUs48States: Set<String> = setOf("AK", "HI", "PR", "GU", "VI", "AS", "MP")

    /** Each state's code in lower case and its name through normalizePlaceName, worked out once. */
    private class StateKey(val code: String, val lower: String, val name: String)

    private val stateKeys: List<StateKey> by lazy {
        usStates.map { StateKey(it.code, it.code.lowercase(), normalizePlaceName(it.name)) }
    }

    /** \b(saint|sainte|mount|fort)\b, in the order the alternation tries them. */
    private val abbreviations = listOf("saint" to "st", "sainte" to "ste", "mount" to "mt", "fort" to "ft")
    private val countries = setOf("usa", "us", "united states", "united states of america")
    private val countyWords = listOf(" county", " parish", " borough", " census area", " municipality")

    /** US_STATES[code], or null for a code that is not a state or territory. */
    fun stateName(code: String): String? = usStates.firstOrNull { it.code == code }?.name

    // Names

    /**
     * normalizePlaceName: puts a place name in one standard form, so "St. Louis", "saint louis" and "St Louis" all
     * match. Drops accents, case, periods and apostrophes; hyphens and slashes become spaces; commas are kept.
     */
    fun normalizePlaceName(text: String): String {
        // .normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase()
        val lower = JsText.unaccentedLower(text)
        // .replace(/[.'’]/g, "").replace(/[-/]/g, " ")
        val a = StringBuilder(lower.length)
        for (c in lower) {
            when (c) {
                '.', '\'', '’' -> {}
                '-', '/' -> a.append(' ')
                else -> a.append(c)
            }
        }
        // .replace(/\s*,\s*/g, ", "): each comma, with the spaces on both sides of it, becomes ", ".
        val out = StringBuilder(a.length)
        var i = 0
        while (i < a.length) {
            if (JsText.isSpace(a[i])) {
                var j = i
                while (j < a.length && JsText.isSpace(a[j])) j++
                if (j < a.length && a[j] == ',') {
                    i = j
                    continue
                }
                out.append(a, i, j)
                i = j
            } else if (a[i] == ',') {
                out.append(", ")
                i++
                while (i < a.length && JsText.isSpace(a[i])) i++
            } else {
                out.append(a[i])
                i++
            }
        }
        // .replace(/\s+/g, " ").trim(), then .replace(/,$/, "").trim()
        var s = JsText.trimmed(JsText.collapsedSpaces(out))
        if (s.endsWith(",")) s = JsText.trimmed(s.substring(0, s.length - 1))
        // .replace(/\b(saint|sainte|mount|fort)\b/g, ...)
        fun isWordAt(k: Int) = k >= 0 && k < s.length && JsText.isWordChar(s[k])
        val result = StringBuilder(s.length)
        var k = 0
        scan@ while (k < s.length) {
            if (isWordAt(k) && !isWordAt(k - 1)) {
                for ((long, short) in abbreviations) {
                    if (s.startsWith(long, k) && !isWordAt(k + long.length)) {
                        result.append(short)
                        k += long.length
                        continue@scan
                    }
                }
            }
            result.append(s[k])
            k++
        }
        return result.toString()
    }

    /** dropCountry: .replace(/,? (usa|us|united states|united states of america)$/, "").trim() */
    internal fun dropCountry(s: String): String {
        for (p in s.indices) {
            val rest: String? = when {
                s[p] == ',' && p + 1 < s.length && s[p + 1] == ' ' -> s.substring(p + 2)
                s[p] == ' ' -> s.substring(p + 1)
                else -> null
            }
            if (rest != null && rest in countries) return JsText.trimmed(s.substring(0, p))
        }
        return JsText.trimmed(s)
    }

    /** stateCode: the two-letter code for normalized text that is only a state ("ohio", "oh", "state of ohio"), else null. */
    internal fun stateCode(s: String): String? {
        // .replace(/^(the )?state of /, "").replace(/ state$/, "").trim()
        var t = when {
            s.startsWith("the state of ") -> s.substring("the state of ".length)
            s.startsWith("state of ") -> s.substring("state of ".length)
            else -> s
        }
        if (t.endsWith(" state")) t = t.substring(0, t.length - " state".length)
        t = JsText.trimmed(t)
        for (key in stateKeys) if (t == key.lower || t == key.name) return key.code
        if (t == "dc" || t == "washington dc" || t == "washington, dc") return "DC"
        return null
    }

    /**
     * displayName: capitalizes each word of what the visitor typed, for messages: "springfield" -> "Springfield".
     * .trim().replace(/\s+/g, " ").replace(/(^|[\s-])(\p{Ll})/gu, upper case). The u flag makes the last step work on
     * code points, so it does too.
     */
    fun displayName(text: String): String {
        val a = JsText.collapsedSpaces(JsText.trimmed(text))
        val out = StringBuilder(a.length)
        var i = 0
        var previous = -1
        while (i < a.length) {
            val cp = a.codePointAt(i)
            val n = Character.charCount(cp)
            val afterBreak = previous == -1 || previous == ' '.code || previous == '-'.code
            if (afterBreak && Character.getType(cp) == Character.LOWERCASE_LETTER.toInt()) {
                out.append(String(Character.toChars(cp)).uppercase())
            } else {
                out.appendCodePoint(cp)
            }
            previous = cp
            i += n
        }
        return out.toString()
    }

    // What was typed

    /** /^\d{5}(?:-?\d{4})?$/: five digits, nine digits, or five digits, a dash and four digits. */
    private fun isZipText(d: String): Boolean = when (d.length) {
        5, 9 -> d.all(JsText::isDigit)
        10 -> d.substring(0, 5).all(JsText::isDigit) && d[5] == '-' && d.substring(6).all(JsText::isDigit)
        else -> false
    }

    /** classifyInput. */
    fun classify(text: String): InputKind {
        val a = JsText.trimmed(text)
        if (a.isEmpty()) return InputKind.Empty
        // normalizeZip
        if (isZipText(a)) return InputKind.Zip(a.substring(0, 5))
        // A street address starts with a house number. (/^\d/)
        if (JsText.isDigit(a[0])) return InputKind.Address(a)
        // "Dayton, OH 45402": the ZIP at the end is enough. /(?:^|[\s,])(\d{5})(?:-?\d{4})?$/
        for (q in -1 until a.length) {
            if (q >= 0 && !(JsText.isSpace(a[q]) || a[q] == ',')) continue
            val rest = a.substring(q + 1)
            if (isZipText(rest)) return InputKind.Zip(rest.substring(0, 5))
        }
        // Washington, DC is one county, so it is looked up like a city.
        val code = stateCode(dropCountry(normalizePlaceName(a)))
        if (code != null && code != "DC") return InputKind.State(code, stateName(code) ?: "")
        return InputKind.Place(a)
    }

    // Finding the county

    /**
     * resolveLocation: the full Location for a county code, or OutsideUs48 when the county has no ecoregion or its
     * state is not one of the lower 48 states (plus DC) in `tables.states`.
     */
    fun resolve(countyFips: String, lat: Double?, lon: Double?, tables: Tables): LocationResult {
        val ecoregion = tables.countyEcoregion[countyFips] ?: ""
        val stateFips = countyFips.take(2)
        val state = tables.states[stateFips]
        if (ecoregion.isEmpty() || state == null) return LocationResult.OutsideUs48
        return LocationResult.Found(
            Location(
                countyFips = countyFips,
                countyName = tables.countyNames[countyFips] ?: "",
                stateFips = stateFips,
                stateName = state.name,
                stateUsps = state.usps,
                ecoregion = ecoregion,
                lat = lat,
                lon = lon,
            ),
        )
    }

    /**
     * lookupZip then resolveLocation: NotFound for a ZIP the table does not have (the website's "We couldn't find ZIP
     * ..."), OutsideUs48 for a ZIP whose county is outside the lower 48 or has no ecoregion.
     */
    fun zip(zip: String, tables: Tables): LocationResult {
        val row = tables.zipCounty[zip] ?: return LocationResult.NotFound
        return resolve(row.countyFips, row.lat, row.lon, tables)
    }

    private class Reading(val name: String, val code: String?)

    /** placeReadings: the ways to read normalized text as a name plus an optional state, most likely first. */
    private fun placeReadings(s: String): List<Reading> {
        val out = ArrayList<Reading>()
        val whole = JsText.trimmed(s.replace(",", ""))
        val comma = s.lastIndexOf(',')
        if (comma > 0) {
            val code = stateCode(JsText.trimmed(s.substring(comma + 1)))
            if (code != null) out.add(Reading(JsText.trimmed(s.substring(0, comma).replace(",", "")), code))
            out.add(Reading(whole, null))
        } else {
            // Every state name or code the text ends with, longest first (sortedWith is stable, as in JavaScript).
            val tails = ArrayList<Pair<Reading, Int>>()
            for (key in stateKeys) {
                for (tail in listOf(key.name, key.lower)) {
                    if (s.endsWith(" $tail")) {
                        tails.add(Reading(JsText.trimmed(s.substring(0, s.length - tail.length - 1)), key.code) to tail.length)
                    }
                }
            }
            out.add(Reading(whole, null))
            out.addAll(tails.sortedWith { a, b -> b.second - a.second }.map { it.first })
        }
        if (whole == "dc" || whole == "washington dc" || whole == "district of columbia") out.add(0, Reading("washington", "DC"))
        if (whole == "nyc" || whole == "new york city" || whole == "nyc ny" || whole == "new york city ny") {
            out.add(0, Reading("new york", "NY"))
        }
        return out
    }

    private sealed interface PlaceHit {
        class Place(val countyFips: String, val lat: Double, val lon: Double, val kind: PlaceKind) : PlaceHit
        class Ambiguous(val states: List<String>) : PlaceHit
        data object Outside : PlaceHit
        data object NotFound : PlaceHit
    }

    /** lookupPlace. Like the website, it does not classify the text first, so "Ohio" is looked up as a place name. */
    private fun lookupPlace(text: String, tables: Tables): PlaceHit {
        var outside = false
        for (reading in placeReadings(dropCountry(normalizePlaceName(text)))) {
            if (reading.name.isEmpty()) continue
            val code = reading.code
            if (code != null && code !in tables.coveredStates) {
                outside = true
                continue
            }
            // "Hamilton Co" is Hamilton County. ("Louisville CO" was already tried as Colorado.)
            val name = reading.name
            val keys = if (name.endsWith(" co")) listOf(name, name.substring(0, name.length - 3) + " county") else listOf(name)
            for (key in keys) {
                val rows = (tables.placeCounty[key] ?: emptyList()).filter { code == null || it.state == code }
                if (rows.isEmpty()) continue
                if (rows.size > 1 && !rows[0].isDefault) return PlaceHit.Ambiguous(rows.map { it.state })
                return PlaceHit.Place(rows[0].countyFips, rows[0].lat, rows[0].lon, placeKind(key))
            }
        }
        return if (outside) PlaceHit.Outside else PlaceHit.NotFound
    }

    /** lookupPlace's kind: / (county|parish|borough|census area|municipality)$/ on the table name that matched. */
    private fun placeKind(key: String): PlaceKind =
        if (countyWords.any { key.endsWith(it) }) PlaceKind.County else PlaceKind.City

    /** lookupPlace then resolveLocation, or lookupPlace's error. */
    fun place(text: String, tables: Tables): PlaceResult = when (val hit = lookupPlace(text, tables)) {
        is PlaceHit.Place -> when (val r = resolve(hit.countyFips, hit.lat, hit.lon, tables)) {
            is LocationResult.Found -> PlaceResult.Found(r.location, hit.kind)
            else -> PlaceResult.OutsideUs48(hit.kind)
        }
        is PlaceHit.Ambiguous -> PlaceResult.Ambiguous(hit.states)
        PlaceHit.Outside -> PlaceResult.OutsideUs48(null)
        PlaceHit.NotFound -> PlaceResult.NotFound
    }

    /** EARTH_RADIUS_MI in sellers.js. */
    const val EARTH_RADIUS_MI = 3958.8

    /**
     * distanceMi in sellers.js: straight-line miles between two points (haversine), with the arithmetic in the same
     * order so the results match the website's. StrictMath is fdlibm, the same sin, cos and asin JavaScript engines use.
     */
    fun distanceMi(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        fun rad(deg: Double) = (deg * Math.PI) / 180
        val dLat = rad(bLat - aLat)
        val dLon = rad(bLon - aLon)
        // x ** 2 in JavaScript is exactly x * x.
        val sinLat = StrictMath.sin(dLat / 2)
        val sinLon = StrictMath.sin(dLon / 2)
        val h = sinLat * sinLat + StrictMath.cos(rad(aLat)) * StrictMath.cos(rad(bLat)) * (sinLon * sinLon)
        return 2 * EARTH_RADIUS_MI * StrictMath.asin(minOf(1.0, StrictMath.sqrt(h)))
    }

    /**
     * The website's nearest-ZIP logic, then as a ZIP: the county of the ZIP whose table point is closest to the
     * position. Ties go to the lower ZIP.
     */
    fun nearest(lat: Double, lon: Double, tables: Tables): LocationResult {
        var best: ZipRow? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (code in tables.sortedZips) {
            val row = tables.zipCounty[code] ?: continue
            val d = distanceMi(lat, lon, row.lat, row.lon)
            if (d < bestDistance) {
                bestDistance = d
                best = row
            }
        }
        val found = best ?: return LocationResult.NotFound
        return resolve(found.countyFips, found.lat, found.lon, tables)
    }

    // Words on screen

    /** stateMessage: the message for a typed state, given its two-letter code. */
    fun stateMessage(code: String): String {
        val name = stateName(code) ?: "undefined"
        if (code in outsideUs48States) return "We only cover the lower 48 states for now."
        if (code in oneEcoregionStates) return "Type a city, county, or ZIP code in $name."
        return "$name contains several different ecoregions. Type a city, county, or ZIP code."
    }

    /** ambiguousMessage: the message for a city name that several states share. */
    fun ambiguousMessage(text: String, states: List<String>): String {
        // text.replace(/,.*$/, ""): from the first comma with no line break after it, to the end.
        var cut = text.length
        for (c in text.indices) {
            if (text[c] == ',' && (c + 1 until text.length).none { JsText.isLineTerminator(text[it]) }) {
                cut = c
                break
            }
        }
        val name = displayName(text.substring(0, cut))
        return "There's a $name in more than one state. Add the state, like $name, ${states.firstOrNull() ?: "undefined"}."
    }

    /** headerText. */
    fun headerText(loc: Location?): String {
        if (loc == null) return "Native plants for your spot"
        return "Native plants for ${loc.countyName}, ${loc.stateName}"
    }

    /** coverageText: shows only for an ecoregion the site does not serve yet. */
    fun coverageText(n: Int, served: Boolean): String {
        if (served || n <= 0) return ""
        return "We have $n plants for your area so far."
    }

    /**
     * roundTo2 in location.js (roundPosition in app-logic.mjs): Math.round(x * 100) / 100, two decimals (about 1 km),
     * so a saved position cannot point at a house. Math.round sends halves up, toward +infinity.
     */
    fun roundPosition(x: Double): Double = JsText.mathRound(x * 100) / 100

    /** nativeHere: native to the visitor's ecoregion, and not flagged as introduced in the visitor's state. */
    fun nativeHere(plantId: String, loc: Location, geo: Map<String, PlantGeo>): Boolean {
        val g = geo[plantId] ?: return false
        return loc.ecoregion in g.eco && loc.stateFips !in g.intro
    }
}
