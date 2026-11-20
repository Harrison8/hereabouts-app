// The first-open screen, as the approved picture draws it: the wordmark, "Native plants for your county", a ZIP code
// field with the number pad up, the county found shown under it as the ZIP is typed, and the button pinned above the
// keyboard. The two links for a town or street address, or the phone's own location, are pinned under the button, so
// both can be tapped while the number pad is up. Everything above the button scrolls, and the county found is scrolled
// into view as the keyboard comes up. The lede is left out while the number pad leaves no room for it. At the largest
// text sizes the button and links would leave no room for the field, so they follow the county in the scroll area
// instead. Text follows the phone's text size at every size.
// Settings opens the same screen as a page titled "Change location", without the wordmark, title or lede.
//
// Everything is looked up on the phone from the tables the app ships with, apart from a street address, which goes to
// the Census Bureau's address finder as on the website. The county is set only when the button is tapped. The
// location_set event says how it was found, with the website's values (zip, city, county or address), or device for the
// phone's location, and never carries what was typed or a position.
package garden.hereabouts.app.firstopen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import garden.hereabouts.app.AppModel
import garden.hereabouts.app.Brand
import garden.hereabouts.app.Figtree
import garden.hereabouts.app.R
import garden.hereabouts.app.YoungSerif
import garden.hereabouts.core.InputKind
import garden.hereabouts.core.Location
import garden.hereabouts.core.LocationLookup
import garden.hereabouts.core.LocationResult
import garden.hereabouts.core.PlaceResult
import garden.hereabouts.core.Tables
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The website's lookup (index.html, lookup()), in the same order and with the same messages. */
object FirstOpenLookup {
    sealed interface Outcome {
        /** A county, and how it was found: the website's "zip", "city", "county" or "address", or "device". */
        data class Found(val location: Location, val method: String) : Outcome

        /** The website's message for why no county was found. */
        data class Message(val text: String) : Outcome
    }

    const val EMPTY_MESSAGE = "Type your city, county or ZIP code first."
    const val OUTSIDE_MESSAGE = "We only cover the lower 48 states for now."
    const val PLACE_NOT_FOUND_MESSAGE =
        "We couldn't find that place. Try your ZIP code, or a city and state like Dayton, OH."
    const val NO_MATCH_MESSAGE = "We couldn't match that address. Try your 5-digit ZIP code instead."
    const val NOT_RESPONDING_MESSAGE = "The address lookup isn't responding. Try your 5-digit ZIP code instead."

    fun zipNotFoundMessage(zip: String): String = "We couldn't find ZIP $zip. Try a nearby ZIP or your street address."

    /** A ZIP code as it is typed: null until it has five digits, then its county or the website's message. */
    fun zip(digits: String, tables: Tables): Outcome? = if (digits.length == 5) zipOutcome(digits, tables) else null

    private fun zipOutcome(zip: String, tables: Tables): Outcome = when (val result = LocationLookup.zip(zip, tables)) {
        is LocationResult.Found -> Outcome.Found(result.location, "zip")
        LocationResult.OutsideUs48 -> Outcome.Message(OUTSIDE_MESSAGE)
        LocationResult.NotFound -> Outcome.Message(zipNotFoundMessage(zip))
    }

    /** The phone's position: the county of the nearest ZIP code, kept as that ZIP code's own point. */
    fun device(lat: Double, lon: Double, tables: Tables): Outcome =
        when (val result = LocationLookup.nearest(lat, lon, tables)) {
            is LocationResult.Found -> Outcome.Found(result.location, "device")
            LocationResult.OutsideUs48, LocationResult.NotFound -> Outcome.Message(OUTSIDE_MESSAGE)
        }

    /** A town, county, state, ZIP code or street address, as the website's form takes it. */
    suspend fun text(text: String, tables: Tables, address: AddressLookup): Outcome {
        val addressText: String = when (val kind = LocationLookup.classify(text)) {
            InputKind.Empty -> return Outcome.Message(EMPTY_MESSAGE)
            is InputKind.State -> return Outcome.Message(LocationLookup.stateMessage(kind.code))
            is InputKind.Zip -> return zipOutcome(kind.zip, tables)
            is InputKind.Place -> when (val place = LocationLookup.place(kind.text, tables)) {
                // The website sends the kind of place found: "city" or "county".
                is PlaceResult.Found -> return Outcome.Found(place.location, place.kind.word)
                is PlaceResult.Ambiguous ->
                    return Outcome.Message(LocationLookup.ambiguousMessage(kind.text, place.states))
                is PlaceResult.OutsideUs48 -> return Outcome.Message(OUTSIDE_MESSAGE)
                // A name we don't know that has a number in it may be a street address. (/\d/)
                PlaceResult.NotFound ->
                    if (kind.text.any { it in '0'..'9' }) kind.text else return Outcome.Message(PLACE_NOT_FOUND_MESSAGE)
            }
            is InputKind.Address -> kind.text
        }
        return try {
            when (val result = address.county(addressText)) {
                is LocationResult.Found -> Outcome.Found(result.location, "address")
                LocationResult.OutsideUs48 -> Outcome.Message(OUTSIDE_MESSAGE)
                LocationResult.NotFound -> Outcome.Message(NO_MATCH_MESSAGE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The website says the same for no answer and for no answer in time.
            Outcome.Message(NOT_RESPONDING_MESSAGE)
        }
    }

    /** The first five ASCII digits in what was typed or pasted. */
    fun zipDigits(typed: String): String = typed.filter { it in '0'..'9' }.take(5)
}

/**
 * The website's superseded(): every lookup takes a new number, and a lookup whose number is no longer the latest sets
 * nothing and sends nothing. The number moves on when another lookup starts, when what was typed changes, and when the
 * screen closes (Cancel on the Change location page).
 */
class LatestLookup {
    private val latest = AtomicInteger(0)

    /** Runs one lookup. Its answer, or null when it was overtaken while it waited. */
    suspend fun <T> run(work: suspend () -> T): T? {
        val mine = latest.incrementAndGet()
        val answer = work()
        return if (mine == latest.get()) answer else null
    }

    /** Any lookup still waiting will set nothing. */
    fun supersede() {
        latest.incrementAndGet()
    }
}

private enum class Mode { Zip, Text }

private const val DENIED_MESSAGE = "Hereabouts can't use your location. Try your 5-digit ZIP code instead."
private const val UNAVAILABLE_MESSAGE = "We couldn't find your location. Try your 5-digit ZIP code instead."

private val BodyText = TextStyle(fontFamily = Figtree, fontSize = 17.sp, lineHeight = 24.sp)
private val Corner = RoundedCornerShape(4.dp)

/**
 * The first-open screen. `inSheet` is the Change location page, which has its own title bar and gives the screen its
 * room between that bar and the keyboard; there it leaves out the wordmark, title and lede. `onChosen` runs after the
 * county is set.
 */
@Composable
fun FirstOpenScreen(model: AppModel, modifier: Modifier = Modifier, inSheet: Boolean = false, onChosen: () -> Unit = {}) {
    val data by model.data.collectAsStateWithLifecycle()
    val tables = model.tables
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val myLocation = rememberMyLocation()
    val latest = remember { LatestLookup() }

    var mode by rememberSaveable { mutableStateOf(Mode.Zip) }
    var zip by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var outcome by remember { mutableStateOf(if (mode == Mode.Zip) FirstOpenLookup.zip(zip, tables) else null) }
    var busy by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var firstFocus by remember { mutableStateOf(true) }
    val fieldFocus = remember { FocusRequester() }
    val statusInView = remember { BringIntoViewRequester() }
    val statusAndActionsInView = remember { BringIntoViewRequester() }

    // Heights measured on screen, none of which depends on where the button and links are placed, so the choice made
    // from them cannot flip back and forth. Zero until the first measurement.
    var screenHeight by remember { mutableIntStateOf(0) }
    var fieldsHeight by remember { mutableIntStateOf(0) }
    var statusHeight by remember { mutableIntStateOf(0) }
    val formHeight = fieldsHeight + statusHeight
    // The scroll area's height, and the county with the button and links when they follow it there.
    var viewportHeight by remember { mutableIntStateOf(0) }
    var statusAndActionsHeight by remember { mutableIntStateOf(0) }
    var actionsHeight by remember { mutableIntStateOf(0) }
    var headerHeight by remember { mutableIntStateOf(0) }
    var ledeHeight by remember { mutableIntStateOf(0) }

    // The button and both links stay pinned above the keyboard while they leave room for the label, the field and the
    // county. At the largest text sizes they do not, so they follow the county in the scroll area instead.
    val pinnedHeight = actionsHeight + with(density) { 14.dp.roundToPx() }
    val pinsActions = screenHeight == 0 || pinnedHeight + formHeight + with(density) { 16.dp.roundToPx() } <= screenHeight
    // The lede is left out while the number pad leaves no room for it above the field. The title always stays.
    val room = screenHeight - (if (pinsActions) pinnedHeight else 0) - formHeight
    val showsLede = screenHeight == 0 || headerHeight + ledeHeight <= room

    val found = outcome as? FirstOpenLookup.Outcome.Found

    // The picture's "Show 122 plants": how many plants are native to the county found, or the website's "Show my
    // plants" before one is found.
    val buttonTitle = remember(found, data) {
        val count = found?.let { f -> data.plants.count { LocationLookup.nativeHere(it.id, f.location, data.geo) } } ?: 0
        when (count) {
            0 -> "Show my plants"
            1 -> "Show 1 plant"
            else -> "Show $count plants"
        }
    }
    // A ZIP code needs a county found first. Typed text can also be looked up by the button itself.
    val buttonEnabled = !busy && (found != null || (mode == Mode.Text && outcome == null && text.isNotBlank()))

    fun choose() {
        val chosen = outcome as? FirstOpenLookup.Outcome.Found ?: return
        focusManager.clearFocus()
        model.setLocation(chosen.location, chosen.method)
        onChosen()
    }

    fun lookUpText(thenChoose: Boolean) {
        if (busy) return
        busy = true
        val typed = text
        scope.launch {
            // Null when the text changed, another lookup started or the screen closed while the Census was asked.
            val result = latest.run { FirstOpenLookup.text(typed, tables, AddressLookup(tables)) } ?: return@launch
            busy = false
            outcome = result
            if (thenChoose && result is FirstOpenLookup.Outcome.Found) choose()
        }
    }

    fun useMyLocation() {
        if (busy) return
        busy = true
        focusManager.clearFocus()
        scope.launch {
            val result = latest.run { myLocation.position() } ?: return@launch
            busy = false
            outcome = when (result) {
                is MyLocation.Outcome.Position -> FirstOpenLookup.device(result.lat, result.lon, tables)
                MyLocation.Outcome.Denied -> FirstOpenLookup.Outcome.Message(DENIED_MESSAGE)
                MyLocation.Outcome.Unavailable -> FirstOpenLookup.Outcome.Message(UNAVAILABLE_MESSAGE)
            }
        }
    }

    fun switchMode() {
        mode = if (mode == Mode.Zip) Mode.Text else Mode.Zip
        latest.supersede()
        busy = false
        outcome = if (mode == Mode.Zip) FirstOpenLookup.zip(zip, tables) else null
    }

    // Let the screen settle before the keyboard comes up; after that, the keyboard stays with the field in use.
    LaunchedEffect(mode) {
        if (firstFocus) {
            delay(400)
            firstFocus = false
        }
        fieldFocus.requestFocus()
        keyboard?.show()
    }
    // The county found is kept in view as the keyboard comes up and as the county appears. When the button and links
    // follow it in the scroll area, they are brought into view with it if there is room for all three.
    LaunchedEffect(focused, outcome, screenHeight, pinsActions, formHeight, viewportHeight, statusAndActionsHeight) {
        if (!focused && outcome == null) return@LaunchedEffect
        if (!pinsActions && statusAndActionsHeight in 1..viewportHeight) {
            statusAndActionsInView.bringIntoView()
        } else {
            statusInView.bringIntoView()
        }
    }
    // The screen closed some other way, such as Back: a lookup still waiting sets nothing.
    DisposableEffect(Unit) { onDispose { latest.supersede() } }

    val onButton = { if (found == null && mode == Mode.Text) lookUpText(thenChoose = true) else choose() }
    val switchLabel = if (mode == Mode.Zip) "Use a town or street address instead" else "Use a ZIP code instead"

    Column(
        modifier
            .fillMaxSize()
            .background(Brand.ground)
            .then(if (inSheet) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
            .onSizeChanged { screenHeight = it.height },
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewportHeight = it.height }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            if (!inSheet) {
                // The approved picture's wordmark, title and lede. Tapping them closes the number pad.
                Column(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }) {
                    Column(Modifier.padding(top = 32.dp).onSizeChanged { headerHeight = it.height }) {
                        Image(
                            painterResource(R.drawable.wordmark),
                            contentDescription = "Hereabouts",
                            modifier = Modifier.width(168.dp),
                        )
                        Spacer(Modifier.height(32.dp))
                        Text(
                            "Native plants\nfor your county",
                            style = TextStyle(fontFamily = YoungSerif, fontSize = 34.sp, lineHeight = 41.sp),
                            color = Brand.ink,
                            modifier = Modifier.semantics {
                                contentDescription = "Native plants for your county"
                                heading()
                            },
                        )
                    }
                    Text(
                        if (mode == Mode.Zip) {
                            "Enter your ZIP code to see the plants that grow wild where you live."
                        } else {
                            "Enter your town or street address to see the plants that grow wild where you live."
                        },
                        style = BodyText.copy(lineHeight = 26.sp),
                        color = Brand.inkSoft,
                        modifier = Modifier
                            // Measured at its full height even while it is left out, so leaving it out cannot change
                            // the choice.
                            .then(
                                if (showsLede) {
                                    Modifier
                                } else {
                                    Modifier.clearAndSetSemantics {}.layout { measurable, constraints ->
                                        val placeable = measurable.measure(constraints)
                                        layout(placeable.width, 0) {}
                                    }
                                },
                            )
                            .onSizeChanged { ledeHeight = it.height }
                            .padding(top = 12.dp),
                    )
                }
            }
            // The label, the field and the county found: always in view, above the button.
            Column(Modifier.onSizeChanged { fieldsHeight = it.height }) {
                Text(
                    if (mode == Mode.Zip) "ZIP code" else "Town or street address",
                    style = BodyText.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                    color = Brand.ink,
                    modifier = Modifier.padding(top = if (inSheet) 16.dp else 24.dp),
                )
                val fieldModifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .border(2.dp, Brand.moss, Corner)
                    .focusRequester(fieldFocus)
                    .onFocusChanged { focused = it.isFocused }
                val fieldStyle = TextStyle(fontFamily = Figtree, fontSize = 24.sp, color = Brand.ink)
                val decoration: @Composable (@Composable () -> Unit) -> Unit = { inner ->
                    Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) { inner() }
                }
                if (mode == Mode.Zip) {
                    BasicTextField(
                        value = zip,
                        onValueChange = { typed ->
                            // Only the first five digits count; anything else is taken out as it is typed or pasted.
                            zip = FirstOpenLookup.zipDigits(typed)
                            // "Use my location" still waiting sets nothing once a ZIP code is typed.
                            latest.supersede()
                            busy = false
                            outcome = FirstOpenLookup.zip(zip, tables)
                        },
                        textStyle = fieldStyle,
                        cursorBrush = SolidColor(Brand.moss),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        decorationBox = decoration,
                        modifier = fieldModifier
                            .semantics { contentDescription = "ZIP code" }
                            .testTag("zipField"),
                    )
                } else {
                    BasicTextField(
                        value = text,
                        onValueChange = { typed ->
                            text = typed
                            // A lookup still waiting for the old text sets nothing.
                            latest.supersede()
                            busy = false
                            outcome = null
                        },
                        textStyle = fieldStyle,
                        cursorBrush = SolidColor(Brand.moss),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(onSearch = { lookUpText(thenChoose = false) }),
                        decorationBox = decoration,
                        modifier = fieldModifier
                            .semantics { contentDescription = "Town or street address" }
                            .testTag("placeField"),
                    )
                }
            }
            Column(
                Modifier
                    .bringIntoViewRequester(statusAndActionsInView)
                    .onSizeChanged { statusAndActionsHeight = it.height },
            ) {
                // One line is kept for the county, so the layout does not move when it appears.
                Box(
                    Modifier
                        .onSizeChanged { statusHeight = it.height }
                        .padding(top = 8.dp)
                        .heightIn(min = 24.dp)
                        .bringIntoViewRequester(statusInView),
                ) {
                    Status(busy, outcome)
                }
                if (!pinsActions) {
                    Actions(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        buttonTitle, buttonEnabled, switchLabel, !busy, onButton, { switchMode() }, { useMyLocation() },
                    ) { actionsHeight = it }
                }
            }
        }
        if (pinsActions) {
            Actions(
                Modifier
                    .fillMaxWidth()
                    .background(Brand.ground)
                    .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 4.dp),
                buttonTitle, buttonEnabled, switchLabel, !busy, onButton, { switchMode() }, { useMyLocation() },
            ) { actionsHeight = it }
        }
    }
}

/** The button, then the two links under it. `onHeight` gets their height, without the space around them. */
@Composable
private fun Actions(
    modifier: Modifier,
    buttonTitle: String,
    buttonEnabled: Boolean,
    switchLabel: String,
    linksEnabled: Boolean,
    onButton: () -> Unit,
    onSwitch: () -> Unit,
    onMyLocation: () -> Unit,
    onHeight: (Int) -> Unit,
) {
    Column(modifier.onSizeChanged { onHeight(it.height) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = onButton,
            enabled = buttonEnabled,
            shape = Corner,
            colors = ButtonDefaults.buttonColors(
                containerColor = Brand.moss,
                contentColor = Color.White,
                disabledContainerColor = Brand.moss.copy(alpha = 0.4f),
                disabledContentColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("continue"),
        ) {
            Text(buttonTitle, style = BodyText.copy(fontWeight = FontWeight.SemiBold))
        }
        Column {
            Link(switchLabel, linksEnabled, onSwitch)
            Link("Use my location", linksEnabled, onMyLocation)
        }
    }
}

@Composable
private fun Status(busy: Boolean, outcome: FirstOpenLookup.Outcome?) {
    when {
        busy -> Text("Looking up…", style = BodyText, color = Brand.inkSoft)
        outcome is FirstOpenLookup.Outcome.Found -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Brand.moss,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "${outcome.location.countyName}, ${outcome.location.stateName}",
                style = BodyText.copy(fontWeight = FontWeight.SemiBold),
                color = Brand.moss,
            )
        }
        outcome is FirstOpenLookup.Outcome.Message -> Text(
            outcome.text,
            style = BodyText,
            color = Brand.ink,
            modifier = Modifier.testTag("locationMessage"),
        )
    }
}

/** A plain moss link, for the two other ways to find the county. The whole 48 dp row answers a tap. */
@Composable
private fun Link(title: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            title,
            style = BodyText.copy(fontWeight = FontWeight.SemiBold),
            color = Brand.moss.copy(alpha = if (enabled) 1f else 0.5f),
        )
    }
}
