# SteamShelf

An Android app for browsing your Steam library with **ProtonDB** ratings, **HowLongToBeat** times and
filters that help you decide what to play next.

## Features

- **Sign in through Steam** (OpenID, on Steam's own login page) or paste your SteamID / profile URL.
- **Library**: list or grid of every game you own, with playtime, last played, ProtonDB tier,
  Steam Deck status and HowLongToBeat time on each card, plus a progress bar showing how much of a
  typical main-story run you've played.
- **Filters** (combine freely; options within a group are OR-ed, groups are AND-ed):
  - Play status: never played, played < 2 h, started but not beaten, past main story, played in the last 2 weeks
  - ProtonDB: native Linux, Platinum, Gold, Silver, Bronze, Borked, no reports
  - Steam Deck: Verified, Playable, Unsupported, Unknown
  - Time to beat (HLTB main story): < 5 h, 5–15 h, 15–40 h, 40 h+, no data
  - Players: single-player, co-op, multiplayer, local/split screen, full controller support
  - Steam review score (70 / 80 / 90 %+)
  - Steam tags (searchable, must match all selected)
- **Quick chips** for the common cases: Never played, In progress, Runs great on Linux, Deck Verified, Under 15 h.
- **Sorting** by name, playtime, last played, time to beat, time left to beat, ProtonDB rating,
  Steam reviews or release date.
- **Backlog maths**: the header shows hours played and roughly how many hours are left to beat the
  games currently shown.
- **Random pick** (dice icon) from whatever the filters leave.
- **Game page**: playtime (incl. Steam Deck / Linux), HLTB main / extras / completionist times with poll
  counts, ProtonDB tier with trend and confidence, Deck status, platforms, player modes, controller
  support, tags and description, plus links to the Steam store, SteamDB, ProtonDB and HLTB.
- **Fix HLTB matches**: matching is by name, so tap "Wrong game?" to search HLTB and pick the right
  entry (or mark it as not on HLTB). Hand-picked matches are never overwritten.
- Everything is cached on the device; ratings and times refresh in the background.

## Setup

1. Install the APK (see below) and open the app.
2. Tap **Sign in through Steam** (or enter your SteamID64 / profile URL).
3. Get a free Steam Web API key at <https://steamcommunity.com/dev/apikey>
   (any domain name works, e.g. `localhost`), paste it, and tap **Connect**.

The key is required because Steam only exposes a full library with playtime through its Web API.
It's stored only on the device (excluded from backups) and only sent to `api.steampowered.com`.
If you use another account's SteamID, that profile's *Game details* must be public.

The first sync fetches store data and ProtonDB ratings in seconds; HowLongToBeat lookups run at a
polite pace (~80 games/minute), so a 1,000-game library takes about 10–15 minutes the first time.
Progress is saved as it goes, and results are cached for a month.

## Building

Requirements: a JDK 17 installed (it doesn't need to be the default) and the Android SDK with
platform `android-37.0`. Gradle 9.8 can't run builds on JDK 27 yet, so
`gradle/gradle-daemon-jvm.properties` tells it to find and use an installed JDK 17 for the build,
whatever `java` on your PATH is.

```sh
./gradlew assembleDebug                         # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest                     # unit tests (offline)
LIVE_API_TESTS=1 ./gradlew testDebugUnitTest --tests '*LiveApiTest*'   # checks Steam/ProtonDB/HLTB still work
```

For a phone, build the release APK: `./gradlew assembleRelease` gives
`app/build/outputs/apk/release/app-release.apk` (~2 MB, minified, noticeably smoother than the debug
build). It's signed with this machine's debug key (`~/.android/debug.keystore`), so new builds install
over old ones and keep your data; switch to a dedicated keystore before publishing it anywhere.
Install it with `adb install -r app/build/outputs/apk/release/app-release.apk`, or copy the file to the
phone and open it (Android asks once to allow installs from that app).

## How it works

| Source | Endpoint | Notes |
| --- | --- | --- |
| Steam library | `IPlayerService/GetOwnedGames` | Needs your API key. |
| Steam store data | `IStoreBrowseService/GetItems` | Public, batched 50 games per call: Deck status, platforms, tags, reviews, player modes, artwork. |
| Steam tag names | `IStoreService/GetTagList` | Public. |
| ProtonDB | `protondb.com/api/v1/reports/summaries/{appid}.json` | Unofficial but stable; what ProtonDB's site and browser extensions use. |
| HowLongToBeat | `howlongtobeat.com/api/search/site` | No public API. The app uses the website's own search (token from `…/init`). HLTB renames this endpoint every few months; when that happens the app re-discovers the new path from the site's JavaScript automatically. |

Code layout (`app/src/main/java/dev/steamshelf`):

- `data/steam`, `data/protondb`, `data/hltb`: API clients. `HltbMatcher` maps Steam names to HLTB entries
  (edition suffixes stripped, edit distance + word overlap, release year as a tie-breaker).
- `data/LibraryRepository`: the single source of truth. Syncs, caches to JSON files, refreshes stale entries.
- `model`: games, filters and sorting (`LibraryFilter`, `LibraryQuery`), all plain Kotlin and unit tested.
- `ui`: Jetpack Compose screens (login, library, filters, game page, settings).

Not affiliated with Valve, ProtonDB or HowLongToBeat.
