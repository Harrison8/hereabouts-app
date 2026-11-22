// Reads the expected answers in apps/shared/bundle/expected/ (made by `npm run app:bundle` from the website's own
// code) and compares them with what the Kotlin code gives.
//
// The reader is strict, so a change in the files' shape fails a test instead of being skipped quietly: each file
// may only have the top-level fields listed below, each case only the fields its kind has, and an unknown kind
// fails. Asking a case for a field it does not have, or of another type, fails too.
package garden.hereabouts.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import kotlin.test.fail

/** Compares two JSON values. Numbers are compared as numbers, so 36 and 36.0 are equal; key order does not matter. */
internal fun jsonEquals(a: JsonElement, b: JsonElement): Boolean = when {
    a is JsonNull || b is JsonNull -> a is JsonNull && b is JsonNull
    a is JsonPrimitive && b is JsonPrimitive -> when {
        a.isString || b.isString -> a.isString && b.isString && a.content == b.content
        a.booleanOrNull != null || b.booleanOrNull != null -> a.booleanOrNull == b.booleanOrNull
        else -> a.doubleOrNull != null && a.doubleOrNull == b.doubleOrNull
    }
    a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { jsonEquals(a[it], b[it]) }
    a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { jsonEquals(a.getValue(it), b.getValue(it)) }
    else -> false
}

/** One case of an expected file. Each accessor fails the test when the field is missing or of another type. */
internal class Case(val file: String, val index: Int, val fields: JsonObject) {
    /** The case's kind, or null in a file whose cases have none. */
    val kind: String? get() = (fields["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun where(key: String) = "$file case $index field $key"

    fun has(key: String): Boolean = key in fields

    fun element(key: String): JsonElement = fields[key] ?: fail("${where(key)} is missing")

    fun string(key: String): String {
        val p = element(key) as? JsonPrimitive
        if (p == null || !p.isString) fail("${where(key)} is not text: ${fields[key]}")
        return p.content
    }

    fun double(key: String): Double {
        val p = element(key) as? JsonPrimitive
        val d = p?.takeIf { !it.isString }?.doubleOrNull ?: fail("${where(key)} is not a number: ${fields[key]}")
        return d
    }

    fun bool(key: String): Boolean {
        val p = element(key) as? JsonPrimitive
        return p?.takeIf { !it.isString }?.booleanOrNull ?: fail("${where(key)} is not true or false: ${fields[key]}")
    }

    fun obj(key: String): JsonObject = element(key) as? JsonObject ?: fail("${where(key)} is not an object: ${fields[key]}")

    /** An object field that may also be null. */
    fun objOrNull(key: String): JsonObject? = when (val e = element(key)) {
        is JsonNull -> null
        is JsonObject -> e
        else -> fail("${where(key)} is not an object or null: $e")
    }

    fun strings(key: String): List<String> = Expected.strings(element(key), where(key))

    /** A short description of the case without its answer, for a failure message. */
    fun input(): String = JsonObject(fields.filterKeys { it != "result" && it != "kind" }).toString()
}

internal object Expected {
    /** apps/shared/bundle, from the apps/android folder (the tests' working folder). */
    val bundleDir: File by lazy {
        val dir = File("../shared/bundle").canonicalFile
        if (!dir.resolve("expected").isDirectory) {
            fail("No bundle at ${dir.path} (working folder ${File(".").canonicalPath}). Run `npm run app:bundle` first.")
        }
        dir
    }

    /** The top-level fields each expected file may have. */
    private val fileFields: Map<String, Set<String>> = mapOf(
        "location.json" to setOf("about", "cases"),
        "list.json" to setOf("about", "sortMenu", "cases"),
        "collation.json" to setOf("about", "table", "cases"),
        "sellers.json" to setOf("about", "cases"),
        "links.json" to setOf("about", "cases"),
        "labels.json" to setOf("about", "photoOrder", "cases"),
        "map.json" to setOf("about", "cases"),
        "care.json" to setOf("about", "cases"),
        "analytics.json" to setOf("about", "allowed", "onceKeys", "cases"),
        "home-map.json" to setOf("about", "cases"),
    )

    /** The fields each kind of case may have, besides `kind` and `result`. A null kind is a case with no kind. */
    private val caseFields: Map<String, Map<String?, Set<String>>> = mapOf(
        "location.json" to mapOf<String?, Set<String>>(
            "classify" to setOf("text"),
            "zip" to setOf("zip"),
            "place" to setOf("text"),
            "placeKind" to setOf("text"),
            "coords" to setOf("lat", "lon"),
            "message" to setOf("code", "text", "states"),
            "header" to setOf("location"),
            "coverage" to setOf("n", "served"),
        ),
        "list.json" to mapOf<String?, Set<String>>(null to setOf("also", "ids", "location", "state")),
        "collation.json" to mapOf<String?, Set<String>>(null to setOf("sorted")),
        "sellers.json" to mapOf<String?, Set<String>>(
            null to setOf("plant", "location", "channel", "view"),
            "summary" to setOf("vendor", "category", "entryShipsTo"),
        ),
        "links.json" to mapOf<String?, Set<String>>(null to setOf("entry", "vendor", "ctx")),
        "labels.json" to mapOf<String?, Set<String>>(
            null to setOf(
                "plant", "bloomRange", "height", "spread", "sun", "moisture", "growth", "category", "seasonRows",
                "growingFacts", "mainPhoto", "plantTitle",
            ),
        ),
        // map.json has two shapes of case with no kind (a point, or a region); RangeMapTest checks each case's exact fields.
        "map.json" to mapOf<String?, Set<String>>(null to setOf("lat", "lon", "point", "region", "pathCount", "firstPoint")),
        "care.json" to mapOf<String?, Set<String>>(null to setOf("tz", "now", "county", "savedIds")),
        "analytics.json" to mapOf<String?, Set<String>>(null to setOf("event", "props")),
        "home-map.json" to mapOf<String?, Set<String>>(null to setOf("region", "step")),
    )

    private val parsed = HashMap<String, JsonObject>()

    /** The parsed file apps/shared/bundle/expected/<file>, checked against the fields it may have. */
    fun file(file: String): JsonObject = parsed.getOrPut(file) {
        val allowed = fileFields[file] ?: fail("$file is not a file these tests know")
        val f = bundleDir.resolve("expected").resolve(file)
        val o = json.parseToJsonElement(f.readText(Charsets.UTF_8)) as? JsonObject ?: fail("$file is not a JSON object")
        val unknown = o.keys - allowed
        if (unknown.isNotEmpty()) fail("$file has fields these tests do not know: $unknown")
        o
    }

    /** The `cases` of apps/shared/bundle/expected/<file>. Fails on a kind or field the tests do not know. */
    fun cases(file: String): List<Case> {
        val list = file(file)["cases"] as? JsonArray ?: fail("$file has no cases list")
        val kinds = caseFields.getValue(file)
        return list.mapIndexed { i, e ->
            val o = e as? JsonObject ?: fail("$file case $i is not an object")
            val c = Case(file, i, o)
            if (o.containsKey("kind") && c.kind == null) fail("$file case $i has a kind that is not text")
            val allowed = kinds[c.kind] ?: fail("$file case $i has a kind these tests do not know: ${c.kind}")
            val unknown = o.keys - allowed - setOf("kind", "result")
            if (unknown.isNotEmpty()) fail("$file case $i (${c.kind}) has fields these tests do not know: $unknown")
            if (c.kind != null && "result" !in o) fail("$file case $i (${c.kind}) has no result")
            c
        }
    }

    /** A top-level field other than `cases` (list.json's sortMenu, collation.json's table). */
    fun field(file: String, key: String): JsonElement = file(file)[key] ?: fail("$file has no $key")

    /** The parsed JSON of a file in the bundle, like "data/plants.json". */
    fun bundleJSON(path: String): JsonElement = json.parseToJsonElement(bundleDir.resolve(path).readText(Charsets.UTF_8))

    /** A list of text, failing on anything else. */
    fun strings(e: JsonElement, what: String): List<String> {
        val a = e as? JsonArray ?: fail("$what is not a list: $e")
        return a.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: fail("$what has an item that is not text: $it") }
    }

    /** A Location from its JSON object, failing on a missing or mistyped field. */
    fun location(o: JsonObject, what: String): Location {
        val known = setOf("countyFips", "countyName", "stateFips", "stateName", "stateUsps", "ecoregion", "lat", "lon")
        val unknown = o.keys - known
        if (unknown.isNotEmpty()) fail("$what has fields these tests do not know: $unknown")
        val c = Case(what, 0, o)
        // lat and lon must be there; only an explicit null means the website has no point for the county.
        fun number(k: String): Double? = if (c.element(k) is JsonNull) null else c.double(k)
        return Location(
            countyFips = c.string("countyFips"),
            countyName = c.string("countyName"),
            stateFips = c.string("stateFips"),
            stateName = c.string("stateName"),
            stateUsps = c.string("stateUsps"),
            ecoregion = c.string("ecoregion"),
            lat = number("lat"),
            lon = number("lon"),
        )
    }

    /** The bundle's data/ folder, read once. */
    val data: AppData by lazy { AppData.load(bundleDir.resolve("data")) }

    /** The bundle's tables/ folder, read once. */
    val tables: Tables by lazy { Tables.load(bundleDir.resolve("tables")) }
}
