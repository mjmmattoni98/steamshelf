# SteamShelf

An Android app, and a web app you host yourself, for browsing your Steam library with **ProtonDB** ratings,
**HowLongToBeat** times and filters that help you decide what to play next.

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

## Web app (Raspberry Pi + Tailscale)

The same library, filters and game pages in a browser, served from your own machine. The server runs the
app's sync code (same sources, see `core/`), so it keeps the library, ratings and times fresh every six
hours whether or not a browser is open, and the slow first HowLongToBeat pass doesn't need your phone.

Differences from the app: you sign in through Steam on Steam's own page, and that sign-in also guards the web
app. The first Steam account that signs in owns the server; any other account is turned away. The store
language and country are set in Settings, where the phone follows the device language. The web app keeps its
own data, so statuses and HLTB matches set on the phone don't carry over.

**On the Raspberry Pi** (any 64-bit Raspberry Pi OS, or any Linux machine with Docker and Tailscale):

```sh
git clone <this repository> steamshelf && cd steamshelf
docker compose up -d --build     # builds the server image on the Pi (a few minutes the first time)
./deploy/tailscale-serve.sh      # once: publishes it on your tailnet with HTTPS
```

The script prints the address, e.g. `https://raspberrypi.your-tailnet.ts.net`. It uses port 443 when
`tailscale serve` isn't already using it for something else on the Pi, and 8443 otherwise. Open it on any
device in your tailnet, **Sign in through Steam**, then paste your Steam Web API key (as for the app).
HTTPS through `tailscale serve` needs **MagicDNS** and **HTTPS certificates** turned on in the Tailscale
admin console (DNS page); `tailscale serve` prints a link to do that if they're off.

- **Updating:** `git pull && docker compose up -d --build`. Your data is in the `steamshelf-data` volume and
  survives rebuilds (`docker compose down -v` deletes it).
- **Logs:** `docker compose logs -f`.
- **Port 8080 taken on the Pi?** Put `STEAMSHELF_HOST_PORT=8090` in a `.env` file next to
  `docker-compose.yml` and run `docker compose up -d`. If you had already published SteamShelf, remove the old
  entry first (`sudo tailscale serve --https=443 off`, or 8443) and run `./deploy/tailscale-serve.sh` again.
- **Exposure:** the container only listens on `127.0.0.1`, so only `tailscale serve` (and so only your
  tailnet) can reach it. The API key stays in the volume and is only sent to `api.steampowered.com`.
  Session cookies are signed, HTTPS-only and last 90 days; signing out in Settings ends every session.
- **Phone home screen:** in the browser menu, "Add to Home screen" / "Install app" gives it an icon and its
  own window.

Server settings (environment variables, all optional; see `docker-compose.yml`):

| Variable | Default | Meaning |
| --- | --- | --- |
| `STEAMSHELF_PORT` | `8080` | Port inside the container. |
| `STEAMSHELF_DATA_DIR` | `/data` | Where the API key, settings and cache are kept. |
| `STEAMSHELF_PUBLIC_URL` | worked out per request | The address browsers use. Only needed behind a proxy that doesn't pass the host and `X-Forwarded-Proto`, which `tailscale serve` does. |
| `STEAMSHELF_ALLOW_HTTP` | `false` | Allow signing in over plain HTTP (local development only). |

**Working on the web app:** `./gradlew :server:runDev` starts it on <http://localhost:8080> against a fake
Steam, ProtonDB and HowLongToBeat with a sample library. Open <http://localhost:8080/dev/sign-in> to sign in
without a Steam account (that route exists only in this dev launcher), then enter
`0123456789ABCDEF0123456789ABCDEF` as the API key. `./gradlew :server:installDist` builds a runnable copy
in `server/build/install/steamshelf-server` if you'd rather run it without Docker (Java 17 or newer).

## Building

Requirements: a JDK 17 installed (it doesn't need to be the default) and the Android SDK with
platform `android-37.0`. Gradle 9.8 can't run builds on JDK 27 yet, so
`gradle/gradle-daemon-jvm.properties` tells it to find and use an installed JDK 17 for the build,
whatever `java` on your PATH is.

```sh
./gradlew assembleDebug                         # app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test :server:test               # unit tests (offline, against local fake servers)
./gradlew lintDebug                             # Android lint
LIVE_API_TESTS=1 ./gradlew :core:test --tests '*LiveApiTest*'   # checks Steam/ProtonDB/HLTB still work
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

**CI.** `.github/workflows/ci.yml` runs the unit tests and lint, builds the debug APK, and builds and
smoke-tests the web server's Docker image on every push and pull request. `.github/workflows/live-api.yml` runs the live API tests every Monday (and on demand from the
Actions tab), as an early warning when HowLongToBeat, ProtonDB or Steam change something.

## How it works

| Source | Endpoint | Notes |
| --- | --- | --- |
| Steam library | `IPlayerService/GetOwnedGames` | Needs your API key. |
| Steam store data | `IStoreBrowseService/GetItems` | Public, batched 50 games per call: Deck status, platforms, tags, reviews, player modes, artwork. Requested in the device language. |
| Steam tag names | `IStoreService/GetTagList` | Public, in the device language. |
| ProtonDB | `protondb.com/api/v1/reports/summaries/{appid}.json` | Unofficial but stable; what ProtonDB's site and browser extensions use. |
| HowLongToBeat | `howlongtobeat.com/api/search/site` | No public API. The app uses the website's own search (token from `…/init`). HLTB renames this endpoint every few months; when that happens the app re-discovers the new path from the site's JavaScript automatically. |

Code layout, by Gradle module:

`core/` is the code the app and the web server share: plain Kotlin/JVM with no Android dependencies
(`core/src/main/java/dev/steamshelf`).

- `data/steam`, `data/protondb`, `data/hltb`: API clients. `HltbMatcher` maps Steam names to HLTB entries
  (edition suffixes stripped, edit distance + word overlap, release year as a tie-breaker).
- `data/LibraryRepository`: the single source of truth, including the sync status. Syncs, caches to JSON
  files (written at most every few seconds and when the app goes to the background), refreshes stale entries.
- `model`: games, filters and sorting (`LibraryFilter`, `LibraryQuery`), all plain Kotlin and unit tested.

`app/` is the Android app (`app/src/main/java/dev/steamshelf`):

- `sync`: `SyncWorker` hosts the sync as long-running WorkManager work with a foreground notification.
- `ui`: Jetpack Compose screens (login, library, filters, game page, settings). All text is in
  `res/values/strings.xml`; `ui/common/Labels.kt` maps the model's enums to it.

`server/` is the web app (`server/src/main/kotlin/dev/steamshelf/server`), a Ktor server that renders HTML
with kotlinx.html and uses [htmx](https://htmx.org) (vendored in `resources/static`) for in-page updates:

- `ServerContainer`: the counterpart of the app's `AppContainer`, plus the six-hourly auto-sync.
- `Auth`: Steam OpenID sign-in, the owner lock, signed session cookies and the same-origin check for POSTs.
- `library/LibraryParams`: the library view (filters, sort, search, layout) as URL parameters, so views can
  be bookmarked and Back works. The library page is one GET form: htmx swaps the results and the server
  answers with the canonical URL.
- `routes`, `views`: pages and the htmx fragments they swap in. `Text.kt` has the English text, matching the
  app's `strings.xml`.
- `resources/static`: `app.css` (the app's colours, light and dark), `app.js` (image fallbacks, the filter
  sheet, the match dialog, and live sync progress from `/events`, a server-sent event stream).

Tests: the model and matcher are tested directly. The API clients and the repository run against a local
MockWebServer standing in for Steam, ProtonDB and HowLongToBeat (recorded Steam responses are in
`core/src/test/resources`), with virtual time from kotlinx-coroutines-test. The server tests
(`server/src/test`) run the whole web app in Ktor's test engine against a fake backend
(`fake/FakeBackend.kt`, which `runDev` uses too).

Not affiliated with Valve, ProtonDB or HowLongToBeat.
