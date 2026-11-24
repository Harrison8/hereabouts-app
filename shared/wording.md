# Every word in the phone apps

This is the list of the words each phone app shows. The iPhone app keeps its words in `Localizable.xcstrings`. The Android app keeps them in `strings.xml`. A test in each app's checks compares that file with this list, so the two apps cannot drift apart.

`{name}` stands for text the screen fills in, such as a plant's name. `\n` is a line break.

## In both apps

Each of these is in the iPhone app's string file and in the Android app's string file, word for word.

| Words | Where they appear |
|---|---|
| Hereabouts | App name; the wordmark's spoken label on the first screen |
| Plants | Tab bar; title of the Plants screen |
| Garden | Tab bar |
| Settings | Tab bar; title of the Settings screen |
| My garden | Title of the Garden screen |
| Change location | Settings: spoken name of the Change button and title of its page; Plants: spoken name of the county line |
| Photo credits and licenses | Settings row and the title of its page |
| Native plants\nfor your county | First screen: the title |
| Native plants for your county | First screen: the title as it is spoken |
| ZIP code | First screen: label and spoken name of the ZIP code box |
| Town or street address | First screen: label and spoken name of the address box |
| Looking up… | First screen: while a lookup waits |
| Use a town or street address instead | First screen: link under the button |
| Use a ZIP code instead | First screen: link under the button |
| Use my location | First screen: link under the button |
| No plants saved yet | Garden screen with nothing saved: heading |
| Tap {icon} on any plant to save it here.{reminder} | Garden screen with nothing saved: the line under the heading; {icon} is the bookmark picture, {reminder} is a space and the Saturday reminder sentence, or nothing |
| Tap the save button on any plant to save it here.{reminder} | Garden screen with nothing saved: the same line as it is spoken |
| Each Saturday at 8:00 you’ll get the week’s garden jobs for the plants you saved. | Settings, under Saturday reminders; also the sentence {reminder} stands for |
| Browse plants | Garden screen with nothing saved: button |
| This week | Garden screen: heading of the week's jobs |
| {done} of {total} done | Garden screen: how many of this week's jobs are ticked |
| Nothing to do this week. | Garden screen: when the week has no jobs |
| Tick off: {job} | Garden screen: spoken name of a job's tick circle |
| Done | Button that closes a sheet; spoken state of a ticked job |
| Not done | Spoken state of a job that is not ticked |
| Mark as done | Job sheet: button |
| Mark as not done | Job sheet: button once the job is ticked |
| Saved plants | Garden screen: heading of the saved plants |
| 1 plant | Garden screen: spoken count of saved plants |
| {n} plants | Garden screen: spoken count of saved plants |
| Nothing to do this week | Garden screen: line under a saved plant with no job this week |
| 1 job this week | Garden screen: line under a saved plant |
| {n} jobs this week | Garden screen: line under a saved plant |
| Remove | Garden screen: the accessibility action on a saved plant |
| Or: {job} | Job sheet: heading of a second job that shares the row |
| Your {plant} | Job sheet: line beside the plant's photo |
| When | Job sheet: label |
| What to do | Job sheet: label |
| Sources | Job sheet: label |
| Opens outside the app | Spoken hint on every link that leaves the app |
| Save {plant} | Spoken name of every save button |
| Remove {plant} | Garden screen: spoken name of the swipe-to-remove button on a saved plant |
| Saved | Spoken state of a saved plant; the plant page button once saved |
| Not saved | Spoken state of a plant that is not saved |
| Save | Plant page: button |
| Where to buy | Plant page: button and row; heading of the sheet |
| Through the year | Plant page: heading of the month chart |
| How to grow it | Plant page: heading |
| Where it grows wild | Plant page: heading of the range map |
| USDA plant profile | Plant page: link |
| No. {usda} · Regions from the US EPA | Plant page: line under the USDA link |
| No photograph yet | Plate shown where a plant has no photo |
| Directions | Where to buy sheet: button on a nearby seller |
| Directions to {seller} | Where to buy sheet: spoken name of the Directions button |
| Each link opens in your browser. Some of these links earn us a commission. | Where to buy sheet: note at the bottom |
| How this works | Where to buy sheet: link under the note |
| Filters | Plants screen: chip; heading of the filters sheet |
| Clear all | Filters sheet: button |
| Set your location first | Filters sheet: note under Pickup / ship when no county is set |
| Maximum mature height in feet | Filters sheet: spoken name of the height slider |
| {n} chosen | Plants screen: spoken value of the Filters chip |
| All | Plants screen: chip |
| Search {n} plants | Plants screen: search box |
| Sort | Plants screen: spoken name of the sort menu |
| No signal. Seller links open once you have signal again. | Plants screen and Where to buy sheet: banner when the phone has no signal |
| Your location | Settings: group name |
| Change | Settings: button beside the county |
| Reminders | Settings: group name |
| Saturday reminders | Settings: switch; name of the notification channel on Android |
| About Hereabouts | Settings: group name |
| Privacy policy | Settings: link |
| How affiliate links work | Settings: link |
| This app | Settings: group name |
| Version | Settings: row |
| Plant and seller data | Settings: row; its value is the date the data was written |
| Hereabouts can't use your location. Try your 5-digit ZIP code instead. | First screen: message when the phone's location is refused |
| We couldn't find your location. Try your 5-digit ZIP code instead. | First screen: message when the phone gives no position |
| {plant}: no photograph yet | Spoken name of a photo frame with no photo |

## iPhone only

In the iPhone app's string file only. Each follows how that phone works.

| Words | Where they appear |
|---|---|
| Cancel | Change location sheet: button |

## Android only

In the Android app's string file only. Each follows how that phone works.

| Words | Where they appear |
|---|---|
| Back | Spoken name of the back arrow on a page |

## Built in code, the same words in both apps

These words are put together in the apps' code, not read from a string file. Most come from the website's own code, ported line for line, and are not changed here. A test checks that the pieces of each one are in the code of both apps.

| Words | Where they appear |
|---|---|
| Type your city, county or ZIP code first. | First screen: message (from the website) |
| We only cover the lower 48 states for now. | First screen: message (from the website) |
| We couldn't find that place. Try your ZIP code, or a city and state like Dayton, OH. | First screen: message (from the website) |
| We couldn't match that address. Try your 5-digit ZIP code instead. | First screen: message (from the website) |
| The address lookup isn't responding. Try your 5-digit ZIP code instead. | First screen: message (from the website) |
| We couldn't find ZIP {zip}. Try a nearby ZIP or your street address. | First screen: message (from the website) |
| Show my plants | First screen: button before a county is found (from the website) |
| Show 1 plant | First screen: button (from the website) |
| Show {n} plants | First screen: button; also the button of the filters sheet (from the website) |
| This week in your garden | Title of every Saturday reminder (from the website) |
| From {county}’s frost dates. A reminder comes every Saturday at 8:00. | Garden screen: footer under the week's jobs (from the website) |
| From {county}’s frost dates. | Garden screen: footer when reminders are off (from the website) |
| Hereabouts has no frost dates for {county} yet. Without them it shows no jobs. | Garden screen: footer when the county has no frost dates (from the website) |
| Any time of year. | Job sheet: when a job runs all year (from the website) |
| Now until about {date}. | Job sheet: when a job is due this week (from the website) |
| From about {date} to about {date}. | Job sheet: when a job is not due yet (from the website) |
| {dates}. This job runs from {from} to {to}. | Job sheet: how the dates were worked out, built from the county's frost dates (from the website) |
| {n} native {plants} | Plants screen: count above the list, with plant or plants (from the website) |
| No plants match “{search}”. | Plants screen: empty list after a search (from the website) |
| No plants match these filters. Try turning one off. | Plants screen: empty list after filtering (from the website) |
| Also matching, but not native to {county}, {state}: | Plants screen: line under the results while searching (from the website) |
| Also matching, but hidden by your filters: | Plants screen: line under the results while searching (from the website) |
| No seller listed yet | Plant page: line under Where to buy (from the website) |
| {n} sellers ship to {state} | Plant page: line under Where to buy (from the website) |
| Pick up · {miles} mi · {place} | Where to buy sheet: line under a nearby seller (from the website) |
| None has a shop near you. | Where to buy sheet: summary (from the website) |
| The shaded parts of this map of the lower 48 states are where {plant} grows wild. | Plant page: spoken description of the range map (from the website) |
| {months} | Plant page: spoken description of the month chart (from the website) |
| {type} · {Native or Not native} to {county}, {state} | Plant page: line under the Latin name (from the website) |
| In bloom | Plant page: season over a photo (from the website) |
| In fruit | Plant page: season over a photo (from the website) |
| In leaf | Plant page: season over a photo; row of the month chart (from the website) |
| In fall | Plant page: season over a photo (from the website) |
| In winter | Plant page: season over a photo (from the website) |
| Dormant | Plant page: season over a photo (from the website) |
| Full sun (6+ hrs) | Filters sheet: filter names and their options, as on the website (from the website) |
| Soil moisture | Filters sheet: filter names and their options, as on the website (from the website) |
| Mature height | Filters sheet: filter names and their options, as on the website (from the website) |
| Yard zone / use | Filters sheet: filter names and their options, as on the website (from the website) |
| Pickup / ship | Filters sheet: filter names and their options, as on the website (from the website) |
| Trees | Plants screen: type chips, one for each plant type (from the website) |
| {job}: your {plants}. | Saturday reminder: the body, one line for each job that applies to a saved plant (from the website) |
| We have {n} plants for your area so far. | Plants screen: line above the list for a region the site does not serve yet (from the website) |
| None of our plants are recorded as native to {region} yet. | Plants screen: line above the list when no plant is native to the county (from the website) |
| Type a city, county or ZIP code in {state}. | First screen: message when a state name is typed (from the website) |
| {state} contains several different ecoregions. Type a city, county or ZIP code. | First screen: message when a state name is typed (from the website) |
| There's a {place} in more than one state. Add the state, like {place}, {state}. | First screen: message when a city name is shared by several states (from the website) |
| {season} · {n} of {total} | Plant page: counter under the photos |
| {county}, {state} is marked with a dot. | Plant page: second sentence of the spoken description of the range map (from the website) |
| 1 seller sells {plant}. | Where to buy sheet: summary when no county is set (from the website) |
| {n} sellers sell {plant}. | Where to buy sheet: summary when no county is set (from the website) |
| 1 seller ships {plant} to {state}. | Where to buy sheet: summary (from the website) |
| {n} sellers ship {plant} to {state}. | Where to buy sheet: summary (from the website) |
| 1 seller has a shop near you. | Where to buy sheet: summary (from the website) |
| {n} sellers have a shop near you. | Where to buy sheet: summary (from the website) |
| 1 seller | Plant page: line under Where to buy when no county is set (from the website) |
| {n} sellers | Plant page: line under Where to buy when no county is set (from the website) |
| 1 seller near you | Plant page: line under Where to buy (from the website) |
| {n} sellers near you | Plant page: line under Where to buy (from the website) |
| 1 seller ships to {state} | Plant page: line under Where to buy (from the website) |
| Storefront | Where to buy sheet: place of a nearby seller that has no town on file (from the website) |
| {names} and {n} more | Plants screen: the plants listed under Also matching (from the website) |
| {names} · until {date} | Garden screen: line under a job; the names are the saved plants it applies to (from the website) |
| any | Filters sheet: value of the height filter at its top end (from the website) |
| {n} ft | Filters sheet: value of the height filter (from the website) |
| {county}’s {event} is about {date} | Job sheet: first part of how the dates were worked out (from the website) |
| its {event} about {date} | Job sheet: second part of how the dates were worked out, joined to the first with and (from the website) |
| {month} {day} to {day} | Garden screen: dates of the week, beside This week |
| {month} {day} to {month} {day} | Garden screen: dates of a week that crosses into the next month |
| Native | Plant page: first word of the line under the Latin name (from the website) |
| Not native | Plant page: first word of the line under the Latin name (from the website) |
| last spring frost | Job sheet: name of a date in the county's frost dates (from the website) |
| first fall frost | Job sheet: name of a date in the county's frost dates (from the website) |
| last hard freeze | Job sheet: name of a date in the county's frost dates (from the website) |
| first hard freeze | Job sheet: name of a date in the county's frost dates (from the website) |
| first week with highs of 50°F | Job sheet: name of a date in the county's frost dates (from the website) |
| the {event} | Job sheet: the start or end of a job (from the website) |
| {amount} before {target} | Job sheet: the start or end of a job (from the website) |
| {amount} after {target} | Job sheet: the start or end of a job (from the website) |
| one week | Job sheet: an amount of time (from the website) |
| {count} weeks | Job sheet: an amount of time; the number is a word from two to nine and a number above that (from the website) |
| one day | Job sheet: an amount of time (from the website) |
| {n} days | Job sheet: an amount of time (from the website) |
| Height | Plant page and plant card: label of a fact (from the website) |
| Width | Plant page and plant card: label of a fact (from the website) |
| Sun | Plant page and plant card: label of a fact (from the website) |
| Soil | Plant page and plant card: label of a fact (from the website) |
| Growth | Plant page: label of a fact (from the website) |
| Feeds | Plant page: label of a fact (from the website) |
| Flowers | Plant page: label of a fact (from the website) |
| Bloom | Plant page: row of the month chart (from the website) |
| Fruit | Plant page: row of the month chart (from the website) |
| Fall color | Plant page: row of the month chart (from the website) |
| full sun | Plant page: value of Sun (from the website) |
| part shade | Plant page: value of Sun (from the website) |
| full shade | Plant page: value of Sun (from the website) |
| dry | Plant page: value of Soil (from the website) |
| medium | Plant page: value of Soil (from the website) |
| wet | Plant page: value of Soil (from the website) |
| {a} to {b} | Plant page: a range of Sun or Soil (from the website) |
| {a} or {b} | Plant page: two values of Sun or Soil that are not next to each other (from the website) |
| {a}–{b} years to full size | Plant page: value of Growth (from the website) |
| bees | Plant page: value of Feeds (from the website) |
| butterflies | Plant page: value of Feeds (from the website) |
| hummingbirds | Plant page: value of Feeds (from the website) |
| birds | Plant page: value of Feeds (from the website) |
| caterpillars | Plant page: value of Feeds (from the website) |
| Tree | Plant page: plant type (from the website) |
| Shrub | Plant page: plant type (from the website) |
| Perennial | Plant page: plant type (from the website) |
| Grass / Sedge | Plant page: plant type (from the website) |
| Fern | Plant page: plant type (from the website) |
| Vine | Plant page: plant type (from the website) |
| Groundcover | Plant page: plant type (from the website) |

## Not words anyone reads

- The test-only marks "Opening played", "Opening held" and "Opening" exist in debug builds started by a screen test and are hidden from everyone else.
- Plant names, seller names and prices, the plant descriptions, the care text of each job, the county and state names, month names and photo credits come from the website's data files, shown as they are.
- The iPhone's location question is in `Info.plist`, in the next section.

## The iPhone's location question

| Words | Where they appear |
|---|---|
| Hereabouts finds your county from your location on this phone. The location itself never leaves the phone. | The question the iPhone asks before it gives the app your location (`NSLocationWhenInUseUsageDescription` in Info.plist). Android shows its own system question. |
