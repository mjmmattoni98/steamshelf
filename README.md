# SteamShelf

An Android app for browsing your Steam library with **ProtonDB** ratings, **HowLongToBeat** times and
filters that help you decide what to play next.

## Features

- **Sign in through Steam** (OpenID, on Steam's own login page) or paste your SteamID / profile URL.
- **Library**: list or grid of every game you own, with playtime, last played, ProtonDB tier,
  Steam Deck status and HowLongToBeat time on each card, plus a progress bar showing how much of a
  typical main-story run you've played.
- **Filters** (combine freely; options within a group are OR-ed, groups are AND-ed):
  - Play status: never played, played < 2 h, started but not beaten, past main story, played in the last 2 weeks,
    dropped; plus "show hidden games"
  - ProtonDB: native Linux, Platinum, Gold, Silver, Bronze, Borked, no reports
  - Steam Deck: Verified, Playable, Unsupported, Unknown
  - Time to beat (HLTB main story): < 5 h, 5–15 h, 15–40 h, 40 h+, no data; "match needs review" for
    automatic HLTB matches that are only a fuzzy fit
  - Players: single-player, co-op, multiplayer, local/split screen, full controller support
  - Steam review score (70 / 80 / 90 %+)
  - Steam tags (searchable, must match all selected)
- **Quick chips** for the common cases: Never played, In progress, Runs great on Linux, Deck Verified, Under 15 h,
  Played recently, and "Check HLTB matches" when some matches are uncertain.
- **Sorting** by name, playtime, last played, time to beat, time left to beat, ProtonDB rating,
  Steam reviews or release date.
- **Backlog maths**: the header shows hours played and roughly how many hours are left to beat the
  games currently shown.
- **Random pick** (dice icon) from whatever the filters leave.
- **Game page**: playtime (incl. Steam Deck / Linux), HLTB main / extras / completionist times with poll
  counts, ProtonDB tier with trend and confidence, Deck status, platforms, player modes, controller
  support, tags and description, plus links to the Steam store, SteamDB, ProtonDB and HLTB.
- **Fix HLTB matches**: matching is by name, so an uncertain automatic match shows a warning on its HLTB
  badge. On the game page, tap "It's right" to accept it, or "Wrong game?" to search HLTB and pick the right
  entry (or mark it as not on HLTB). Matches you pick or confirm are never overwritten.
- **Your status**: on the game page, mark a game Finished (counts as beaten whatever the playtime),
  Dropped (off the backlog and out of "in progress") or Hidden (out of the list, stats and random pick, for
  soundtracks, tools and the like). Automatic, the default, derives "beaten" from playtime vs. HLTB.
  Statuses and hand-picked matches survive "Clear cache" and "Refresh everything", and are removed on sign-out.
- Everything is cached on the device; ratings and times refresh in the background. Steam store text and
  tag names follow the device language (English where Steam has no translation).

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
The sync runs in the background with a progress notification (with a Stop button), so you can leave the
app; if Android stops it, it resumes later. On Android 13+ the app asks once to show that notification;
saying no is fine, the sync runs anyway. Progress is saved every few seconds, and results are cached for a
month.

The library refreshes when you open the app (or return to it) and the last sync is more than six hours
old, or when the device language changed. Turning ProtonDB or HowLongToBeat on in Settings fetches it
straight away, and changing "Include free games" reloads the library; if a sync is running, it does an
extra pass at the end to pick the change up. Turning a source off stops it mid-sync.

## Building

Requirements: a JDK 17 installed (it doesn't need to be the default) and the Android SDK with
platform `android-37.0`. Gradle 9.8 can't run builds on JDK 27 yet, so
`gradle/gradle-daemon-jvm.properties` tells it to find and use an installed JDK 17 for the build,
whatever `java` on your PATH is.

```sh
./gradlew assembleDebug                         # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest                     # unit tests (offline, against local fake servers)
./gradlew lintDebug                             # Android lint
LIVE_API_TESTS=1 ./gradlew testDebugUnitTest --tests '*LiveApiTest*'   # checks Steam/ProtonDB/HLTB still work
```

For a phone, build the release APK: `./gradlew assembleRelease` gives
`app/build/outputs/apk/release/app-release.apk` (~2 MB, minified, noticeably smoother than the debug
build). Install it with `adb install -r app/build/outputs/apk/release/app-release.apk`, or copy the file to
the phone and open it (Android asks once to allow installs from that app).

**Signing.** Without any configuration the release APK is signed with this machine's debug key
(`~/.android/debug.keystore`), so new builds install over old ones and keep your data. To sign with your
own key, create `keystore.properties` next to `settings.gradle.kts` (it's git-ignored, as are `*.jks`,
`*.keystore` and `*.p12`):

```properties
# relative to the project root, or an absolute path
storeFile=release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

or set `STEAMSHELF_KEYSTORE`, `STEAMSHELF_KEYSTORE_PASSWORD`, `STEAMSHELF_KEY_ALIAS` and
`STEAMSHELF_KEY_PASSWORD` (handy in CI). Switching keys means uninstalling the old build first.

**Version.** `versionCode` and `versionName` default to `1` / `1.0.0`; override them with
`./gradlew assembleRelease -PversionCode=2 -PversionName=1.1.0` (or `ORG_GRADLE_PROJECT_versionCode` /
`ORG_GRADLE_PROJECT_versionName` in CI).

**CI.** `.github/workflows/ci.yml` runs the unit tests and lint and builds the debug APK on every push and
pull request. `.github/workflows/live-api.yml` runs the live API tests every Monday (and on demand from the
Actions tab), as an early warning when HowLongToBeat, ProtonDB or Steam change something.

## How it works

| Source | Endpoint | Notes |
| --- | --- | --- |
| Steam library | `IPlayerService/GetOwnedGames` | Needs your API key. |
| Steam store data | `IStoreBrowseService/GetItems` | Public, batched 50 games per call: Deck status, platforms, tags, reviews, player modes, artwork. Requested in the device language. |
| Steam tag names | `IStoreService/GetTagList` | Public, in the device language. |
| ProtonDB | `protondb.com/api/v1/reports/summaries/{appid}.json` | Unofficial but stable; what ProtonDB's site and browser extensions use. |
| HowLongToBeat | `howlongtobeat.com/api/search/site` | No public API. The app uses the website's own search (token from `…/init`). HLTB renames this endpoint every few months; when that happens the app re-discovers the new path from the site's JavaScript automatically. |

Code layout (`app/src/main/java/dev/steamshelf`):

- `data/steam`, `data/protondb`, `data/hltb`: API clients. `HltbMatcher` maps Steam names to HLTB entries
  (edition suffixes stripped, edit distance + word overlap, release year as a tie-breaker).
- `data/LibraryRepository`: the single source of truth, including the sync status. Syncs, caches to JSON
  files (written at most every few seconds and when the app goes to the background), refreshes stale entries.
- `sync`: `SyncWorker` hosts the sync as long-running WorkManager work with a foreground notification.
- `model`: games, filters and sorting (`LibraryFilter`, `LibraryQuery`), all plain Kotlin and unit tested.
- `ui`: Jetpack Compose screens (login, library, filters, game page, settings). All text is in
  `res/values/strings.xml`; `ui/common/Labels.kt` maps the model's enums to it.

Tests (`app/src/test`): the model and matcher are tested directly; the API clients and the repository run
against a local MockWebServer standing in for Steam, ProtonDB and HowLongToBeat (with recorded Steam
responses in `app/src/test/resources`), with virtual time from kotlinx-coroutines-test.

Not affiliated with Valve, ProtonDB or HowLongToBeat.
