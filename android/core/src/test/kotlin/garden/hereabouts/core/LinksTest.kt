// Checks the links to sellers against the website's: every case in apps/shared/bundle/expected/links.json is
// buyUrl(entry, vendors[vendor], ctx) from affiliates.js, for every seller entry of every plant and some crafted odd
// addresses. Also the button's safeHref step and the Google Maps directions link from index.html.
package garden.hereabouts.core

import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LinksTest {
    private val ctxFields = setOf("ecoregion", "plantId", "siteDomain", "channel")

    @Test
    fun everyLinkCase() {
        val data = Expected.data
        val cases = Expected.cases("links.json")
        val bad = ArrayList<String>()
        var crafted = 0
        for (c in cases) {
            val ctxObject = c.obj("ctx")
            if (ctxObject.keys != ctxFields) {
                bad.add("case ${c.index}: ctx has fields ${ctxObject.keys.sorted()}")
                continue
            }
            val ctx = Case("links.json ctx", c.index, ctxObject)
            val e = Case("links.json entry", c.index, c.obj("entry"))
            val name = c.string("vendor")
            val plantId = ctx.string("plantId")
            val siteDomain = ctx.string("siteDomain")
            val channel = Channel.fromKey(ctx.string("channel"))
            if (channel == null || e.string("name") != name) {
                bad.add("case ${c.index}: unknown channel, or the entry is not the vendor's")
                continue
            }
            fun text(k: String): String? = if (!e.has(k) || e.element(k) is JsonNull) null else e.string(k)
            val entry = VendorEntry(
                name = name,
                url = text("url"),
                price = text("price"),
                size = text("size"),
                shipsTo = if (e.has("shipsTo")) e.strings("shipsTo") else null,
            )
            val vendor = data.vendors[name] ?: Vendor(name = name)
            if (data.vendors[name] == null) crafted++
            val ecoregion = if (ctx.element("ecoregion") is JsonNull) null else ctx.string("ecoregion")
            val want = if (c.element("result") is JsonNull) null else c.string("result")
            val got = Links.buyUrl(entry, vendor, ecoregion, plantId, channel, siteDomain)
            if (got != want) {
                bad.add("case ${c.index} ($name, ${entry.url ?: "no url"}, ${ecoregion ?: "null"}, ${channel.key}): got $got, expected $want")
            }
        }
        println("links.json: ${cases.size} link cases")
        assertTrue(cases.size > 1000, "only ${cases.size} link cases")
        assertEquals(0, crafted, "every seller in links.json is in vendors.json")
        if (bad.isNotEmpty()) fail("${bad.size} of ${cases.size} link cases differ; first:\n" + bad.take(5).joinToString("\n"))
    }

    @Test
    fun appLinkEndsInApp() {
        val data = Expected.data
        val gfw = assertNotNull(data.vendors["Garden for Wildlife"])
        assertEquals(true, gfw.affiliate)
        val plant = assertNotNull(
            data.plants.firstOrNull { p -> p.vendors.any { it.name == gfw.name && it.url != null } },
            "no plant is sold by Garden for Wildlife",
        )
        val entry = plant.vendors.first { it.name == gfw.name && it.url != null }
        val app = assertNotNull(Links.buyUrl(entry, gfw, "71", plant.id, Channel.App, "hereabouts.garden"))
        val web = assertNotNull(Links.buyUrl(entry, gfw, "71", plant.id, Channel.Web, "hereabouts.garden"))
        assertTrue(app.contains("sca_source=71__${plant.id}__app"), app)
        assertTrue(web.contains("sca_source=71__${plant.id}"), web)
        assertFalse(web.contains("__app"), web)
        assertEquals("71__asclepias-tuberosa__app", Links.subId("71", "asclepias-tuberosa", Channel.App))
        assertEquals("none__asclepias-tuberosa", Links.subId(null, "asclepias-tuberosa", Channel.Web))
        assertEquals("none__x", Links.subId("", "x", Channel.Web))

        // The buy button in the app carries the app tag too.
        val view = Sellers.buyView(plant, null, Channel.App, data)
        val row = assertNotNull(view.rows.firstOrNull { it.name == gfw.name })
        assertTrue(row.href.contains("__app"), row.href)
        assertTrue(view.hasAffiliate)
    }

    @Test
    fun safeHrefKeepsOnlyWebLinks() {
        // index.html's safeHref: the button gets the link itself when it is http or https, and "#" for anything else.
        assertEquals("#", Links.safeHref("mailto:orders@example.com?subject=Aster"))
        assertEquals("#", Links.safeHref("javascript:alert(1)"))
        assertEquals("#", Links.safeHref("not a web address"))
        assertEquals("#", Links.safeHref(""))
        assertEquals("#", Links.safeHref(null))
        assertEquals("https://example.com/plants/aster?size=quart", Links.safeHref("https://example.com/plants/aster?size=quart"))
        assertEquals("http://example.com/plants/aster", Links.safeHref("http://example.com/plants/aster"))
        // The link is kept exactly as written, not tidied: the website passes the text through.
        assertEquals("HTTPS://Example.com/wild aster", Links.safeHref("HTTPS://Example.com/wild aster"))
    }

    @Test
    fun unsupportedWebAddressKeepsAWorkingButton() {
        // Valid http or https addresses that WebUrl does not rewrite. The website tags them; the app sends them out as
        // written, and the button must still work. Seller data reaches phones without an app update, so a seller could
        // add one of these after the app is built.
        val vendor = Vendor(name = "Some Nursery")
        for (url in listOf(
            "https://user@shop.example.com/p", "https://192.168.1.10/p", "https://café.example/p",
            "https://[2001:db8::1]/p",
        )) {
            val link = Links.buyUrl(VendorEntry(name = vendor.name, url = url), vendor, "71", "asclepias-tuberosa", Channel.App, Links.SITE_DOMAIN)
            assertEquals(url, link)
            assertNotEquals("#", Links.safeHref(link), url)
        }
        // The scheme check still comes first, and an address the browser rejects is still not a web address.
        assertEquals("#", Links.safeHref("mailto:user@example.com"))
        assertEquals("#", Links.safeHref("ftp://user@example.com/p"))
        assertEquals("#", Links.safeHref("https://user@/p"))
        assertEquals("#", Links.safeHref("https://user@shop.example.com:99999/p"))
        assertEquals("#", Links.safeHref("https://user@shop example.com/p"))
    }

    @Test
    fun gardenForWildlifeLinkFindsMarksBeforeAccents() {
        // affiliates.js uses indexOf('#') and includes('?'), which look at single UTF-16 units, as Kotlin's do.
        val sub = "71__asclepias-tuberosa"
        val ref = Links.GARDEN_FOR_WILDLIFE_REF
        assertEquals(
            "https://gardenforwildlife.com/products/aster?sca_ref=$ref&sca_source=$sub#́d",
            Links.gardenForWildlifeLink("https://gardenforwildlife.com/products/aster#́d", sub),
        )
        assertEquals(
            "https://gardenforwildlife.com/products/aster?́v=1&sca_ref=$ref&sca_source=$sub",
            Links.gardenForWildlifeLink("https://gardenforwildlife.com/products/aster?́v=1", sub),
        )
        // Not the shop's own https address: the website's program throws, and buyUrl falls back to the tracked link.
        assertNull(Links.gardenForWildlifeLink("http://gardenforwildlife.com/products/aster", sub))
        assertNull(Links.gardenForWildlifeLink("https://example.com/products/aster", sub))
        // A lone half of a surrogate pair makes encodeURIComponent throw on the website: no program link either.
        assertNull(Links.gardenForWildlifeLink("https://gardenforwildlife.com/products/aster", "71__\uD800"))
    }

    @Test
    fun loneSurrogateInAnAddressIsReadAsAReplacementCharacter() {
        // new URL() reads its text as Unicode code points, so a lone half of a surrogate pair becomes U+FFFD,
        // which the path writes as its UTF-8 bytes.
        val vendor = Vendor(name = "Some Nursery")
        assertEquals(
            "https://example.com/a%EF%BF%BDb?utm_source=hereabouts.garden&utm_medium=referral&utm_campaign=71",
            Links.buyUrl(VendorEntry(name = vendor.name, url = "https://example.com/a\uD800b"), vendor, "71", "x", Channel.App, Links.SITE_DOMAIN),
        )
    }

    @Test
    fun queryIsRewrittenTheWayTheBrowserDoes() {
        // searchParams.set re-writes the whole query as form data: "+" and "%20" both become "+", a bare name gets
        // "=", an existing utm tag is replaced in place and a repeat of it is removed.
        val vendor = Vendor(name = "Some Nursery")
        fun buy(url: String) = Links.buyUrl(VendorEntry(name = vendor.name, url = url), vendor, null, "x", Channel.Web, Links.SITE_DOMAIN)
        assertEquals(
            "https://example.com/p?a=b+c&flag=&utm_source=hereabouts.garden&utm_medium=referral&utm_campaign=none",
            buy("https://example.com/p?a=b%20c&flag"),
        )
        assertEquals(
            "https://example.com/p?utm_source=hereabouts.garden&x=1&utm_medium=referral&utm_campaign=none#top",
            buy("https://example.com/p?utm_source=old&x=1&utm_source=again#top"),
        )
        assertEquals(
            "https://example.com/?q=%C3%A9&utm_source=hereabouts.garden&utm_medium=referral&utm_campaign=none",
            buy("HTTPS://EXAMPLE.com:443?q=é"),
        )
        assertEquals(
            "http://example.com:8080/a/c?utm_source=hereabouts.garden&utm_medium=referral&utm_campaign=none",
            buy("http://example.com:8080/a/b/../c"),
        )
    }

    @Test
    fun directionsUrl() {
        // directionsHref: the shop's point, written the way JavaScript writes numbers (38, not 38.0).
        val shop = Vendor(name = "Shop", coords = listOf(34.518252, -89.030319))
        assertEquals("https://www.google.com/maps/dir/?api=1&destination=34.518252%2C-89.030319", Links.directionsUrl(shop))
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=38%2C-85.5",
            Links.directionsUrl(Vendor(name = "Shop", coords = listOf(38.0, -85.5))),
        )
        assertNull(Links.directionsUrl(Vendor(name = "Shop")))
        assertNull(Links.directionsUrl(Vendor(name = "Shop", coords = listOf(38.0))))
    }

    @Test
    fun javaScriptNumberText() {
        assertEquals("38", JsText.numberString(38.0))
        assertEquals("0", JsText.numberString(-0.0))
        assertEquals("38.1", JsText.numberString(38.1))
        assertEquals("-85.58", JsText.numberString(-85.58))
        assertEquals("0.000001", JsText.numberString(0.000001))
        assertEquals("1e-7", JsText.numberString(0.0000001))
        assertEquals("1.5e-7", JsText.numberString(1.5e-7))
        assertEquals("10000000000000000", JsText.numberString(1e16))
        assertEquals("1e+21", JsText.numberString(1e21))
        assertEquals("1.25e+22", JsText.numberString(1.25e22))
        assertEquals("123456.789", JsText.numberString(123456.789))
        // Values whose shortest digits Java's own Double.toString has not always found.
        assertEquals("0.30000000000000004", JsText.numberString(0.1 + 0.2))
        assertEquals("2e-323", JsText.numberString(2.0E-323))
        assertEquals("5e-324", JsText.numberString(Double.MIN_VALUE))
        assertEquals("1.7976931348623157e+308", JsText.numberString(Double.MAX_VALUE))
        assertEquals("9007199254740992", JsText.numberString(9007199254740992.0))
        assertEquals("1.23e-18", JsText.numberString(1.23e-18))
        assertEquals("100", JsText.numberString(100.0))
        assertEquals("NaN", JsText.numberString(Double.NaN))
        assertEquals("-Infinity", JsText.numberString(Double.NEGATIVE_INFINITY))
        assertEquals("71__a-b.c_d!~*'()%C3%A9%20%2C", JsText.encodeURIComponent("71__a-b.c_d!~*'()é ,"))
        assertEquals("%F0%9F%8C%BC", JsText.encodeURIComponent("🌼"))
        assertNull(JsText.encodeURIComponent("\uDC00"))
    }
}
