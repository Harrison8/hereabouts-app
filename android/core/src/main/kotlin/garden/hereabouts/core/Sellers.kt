// Which sellers can sell a plant to a visitor, ported line by line from the website's sellers.js. A seller shows
// only if it has a shop within 75 miles of the visitor, or it ships that kind of plant to the visitor's state.
//
// group is what the plant list needs (the buy filter and the two seller sorts). shipsSummary and buyView build the
// "Where to buy" rows the way buyRowsView in index.html does. The same as the iPhone app's Sellers.swift.
package garden.hereabouts.core

/**
 * One seller's listing for a plant, as the visitor sees it. `miles` is the straight-line distance to the seller's
 * shop, or null when there is no location, the location has no point, or the seller has no coordinates.
 */
data class SellerRow(val entry: VendorEntry, val vendor: Vendor, val miles: Double?)

/** groupSellers' answer: shops within 75 miles (closest first), sellers that ship here, and the two together. */
data class SellerGroups(val nearby: List<SellerRow>, val ships: List<SellerRow>, val all: List<SellerRow>)

object Sellers {
    /** LOCAL_RADIUS_MI: a shop this close counts as nearby. */
    const val LOCAL_RADIUS_MI = 75.0

    /**
     * contiguousUsps(): the postal codes of the lower 48 states and DC. sellers.js reads them from STATE_NAMES in
     * plant-geo.js (the same 49 codes as the bundle's tables/states.json); here they are location.js's states without
     * the ones outside the lower 48. A test checks the two agree.
     */
    val contiguousUsps: Set<String> = LocationLookup.usStates.map { it.code }.toSet() - LocationLookup.outsideUs48States

    /**
     * shipsToState: true if the seller ships this kind of plant to this state. Unknown shipping counts as no. When the
     * plant's own listing has a list of states, that list decides instead of the seller's area.
     */
    fun shipsToState(vendor: Vendor, stateUsps: String, category: String, entry: VendorEntry?): Boolean {
        if (stateUsps.isEmpty()) return false
        if (vendor.shipsExcept?.contains(category) == true) return false
        entry?.shipsTo?.let { return stateUsps in it }
        return when (val to = vendor.shipsTo) {
            ShipsTo.Contiguous48 -> stateUsps in contiguousUsps
            is ShipsTo.States -> stateUsps in to.states
            is ShipsTo.AllExcept -> stateUsps in contiguousUsps && stateUsps !in to.states
            null -> false
        }
    }

    /**
     * groupSellers: splits a plant's sellers into the groups a visitor sees. `nearby` is shops within 75 miles,
     * closest first; `ships` is the sellers that ship here, the ones that pay us first; `all` is nearby then ships.
     * With no location, every seller is in `all`, in data order, and the two groups are empty.
     */
    fun group(p: Plant, loc: Location?, vendors: Map<String, Vendor>): SellerGroups {
        val rows = p.vendors.map { SellerRow(it, vendors[it.name] ?: Vendor(name = it.name), null) }
        if (loc == null) return SellerGroups(emptyList(), emptyList(), rows)
        // Number.isFinite(loc.lat) && Number.isFinite(loc.lon)
        val lat = loc.lat
        val lon = loc.lon
        val hasPoint = lat != null && lon != null && lat.isFinite() && lon.isFinite()
        val nearby = ArrayList<SellerRow>()
        val rest = ArrayList<SellerRow>()
        for (r in rows) {
            var row = r
            val c = row.vendor.coords
            if (hasPoint && c != null && c.size == 2) {
                val d = LocationLookup.distanceMi(lat, lon, c[0], c[1])
                if (d.isFinite()) row = row.copy(miles = d)
            }
            val miles = row.miles
            if (row.vendor.storefront == true && miles != null && miles <= LOCAL_RADIUS_MI) nearby.add(row) else rest.add(row)
        }
        // nearby.sort((a, b) => a.miles - b.miles): a stable sort, so sellers the same distance away keep data order.
        val nearbySorted = nearby.sortedWith { a, b -> JsText.sign((a.miles ?: 0.0) - (b.miles ?: 0.0)) }
        val ships = rest.filter { shipsToState(it.vendor, loc.stateUsps, p.category, it.entry) }
        val affiliateFirst = ships.filter { it.vendor.affiliate == true } + ships.filter { it.vendor.affiliate != true }
        return SellerGroups(nearbySorted, affiliateFirst, nearbySorted + affiliateFirst)
    }

    /** distanceMi: straight-line miles between two points. The haversine itself is LocationLookup's. */
    fun distanceMi(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double = LocationLookup.distanceMi(aLat, aLon, bLat, bLon)

    /** joinStates: "KY and OH", "KY, OH, and TN", a short list joined the way a person writes it, with an Oxford comma. */
    internal fun joinStates(list: List<String>): String {
        // [...codes].sort(): JavaScript compares UTF-16 units, as Kotlin's String.compareTo does.
        val sorted = list.sorted()
        if (sorted.size < 3) return sorted.joinToString(" and ")
        return sorted.dropLast(1).joinToString(", ") + ", and " + sorted.last()
    }

    /**
     * shipsSummary: where a seller sends this kind of plant, for a visitor who has not given a location yet:
     * "Ships to the lower 48 states", "Ships to the lower 48 except CA", "Ships to KY, OH, and TN", "Ships to 35 states".
     * A seller with a shop adds " · Shop in Goshen, KY"; one that does not ship this kind of plant but has a shop says
     * "Pick up only · Goshen, KY". Empty when nothing is known. When the plant's own listing has a list of states,
     * that list is used instead of the seller's area (an empty list too).
     */
    fun shipsSummary(vendor: Vendor, category: String, entry: VendorEntry?): String {
        // `vendor.storefront && vendor.location ? vendor.location : ''`: an empty place counts as none.
        val shop = if (vendor.storefront == true) vendor.location ?: "" else ""
        val excluded = vendor.shipsExcept?.contains(category) == true
        // `entry && Array.isArray(entry.shipsTo) ? entry.shipsTo : vendor.shipsTo`
        val to: ShipsTo? = entry?.shipsTo?.let { ShipsTo.States(it) } ?: vendor.shipsTo
        var ships = ""
        if (!excluded) {
            ships = when (to) {
                ShipsTo.Contiguous48 -> "Ships to the lower 48 states"
                is ShipsTo.AllExcept ->
                    if (to.states.isEmpty()) "Ships to the lower 48 states" else "Ships to the lower 48 except ${joinStates(to.states)}"
                is ShipsTo.States -> when {
                    to.states.isEmpty() -> ""
                    to.states.size <= 10 -> "Ships to ${joinStates(to.states)}"
                    else -> "Ships to ${to.states.size} states"
                }
                // No shipping area: nothing to say about shipping.
                null -> ""
            }
        }
        if (ships.isEmpty()) return if (shop.isEmpty()) "" else "Pick up only · $shop"
        return if (shop.isEmpty()) ships else "$ships · Shop in $shop"
    }

    /**
     * buyRowsView in index.html: the sellers a visitor can use for one plant, ready to draw. `channel` is App in the
     * phone app, which tags affiliate links with "__app"; the site's name in the links is always hereabouts.garden.
     */
    fun buyView(p: Plant, loc: Location?, channel: Channel, data: AppData): BuyView {
        val groups = group(p, loc, data.vendors)
        // `ctx.ecoregion = loc ? loc.ecoregion : null`
        val ecoregion = loc?.ecoregion
        val rows = groups.all.mapIndexed { i, r ->
            // nearbySet.has(r): `all` is nearby then ships, so the first nearby.size rows are the nearby ones.
            val near = i < groups.nearby.size
            // Math.round(r.miles): halves round up. A nearby row always has miles.
            val miles: Int? = if (near) JsText.mathRound(r.miles ?: 0.0).toInt() else null
            val label = when {
                loc == null -> null
                miles != null -> "LOCAL · $miles mi"
                else -> "Ships to you"
            }
            // `shipsSummary(...) || null`: an empty summary is null.
            val summary = if (loc == null) shipsSummary(r.vendor, p.category, r.entry) else ""
            val href = Links.safeHref(Links.buyUrl(r.entry, r.vendor, ecoregion, p.id, channel, Links.SITE_DOMAIN))
            BuyRow(
                name = r.entry.name,
                short = nonEmpty(r.vendor.short) ?: r.entry.name, // `r.vendor.short || r.entry.name`
                place = nonEmpty(r.vendor.location), // `|| null`
                href = href,
                label = label,
                ships = nonEmpty(summary),
                local = near,
                miles = miles,
                kind = if (near) "directions" else "shop",
                directions = if (near) Links.directionsUrl(r.vendor) else null,
                affiliate = r.vendor.affiliate == true, // `!!r.vendor.affiliate`
                price = nonEmpty(r.entry.price),
                size = nonEmpty(r.entry.size),
            )
        }
        // `loc && rows.length === 0 ? ... : null`
        val noSellerText = if (loc != null && rows.isEmpty()) "No seller in our list sells this to ${loc.stateName} yet." else null
        return BuyView(rows, noSellerText, rows.any { it.affiliate })
    }

    /** JavaScript's `x || null` for text: empty text and null are both null. */
    private fun nonEmpty(s: String?): String? = if (s.isNullOrEmpty()) null else s
}

/**
 * One row of the "Where to buy" list, the same 13 fields as the website's row object. `href` is the button's link
 * as the website writes it (the seller's link, or "#" when it is not a web address).
 */
data class BuyRow(
    val name: String,
    val short: String,
    val place: String?,
    val href: String,
    val label: String?,
    val ships: String?,
    val local: Boolean,
    val miles: Int?,
    val kind: String,
    val directions: String?,
    val affiliate: Boolean,
    val price: String?,
    val size: String?,
)

/** buyRowsView's answer: the rows, the line shown when no seller can sell here, and whether any row pays us. */
data class BuyView(val rows: List<BuyRow>, val noSellerText: String?, val hasAffiliate: Boolean)
