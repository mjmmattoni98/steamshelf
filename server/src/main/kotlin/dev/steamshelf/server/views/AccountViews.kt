package dev.steamshelf.server.views

import dev.steamshelf.data.SyncPrefs
import dev.steamshelf.model.Account
import dev.steamshelf.model.SyncStatus
import dev.steamshelf.server.Format
import dev.steamshelf.server.STORE_LANGUAGES
import dev.steamshelf.server.ServerPrefs
import dev.steamshelf.server.label
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.HTMLTag
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.img
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.summary

private const val API_KEY_PAGE = "https://steamcommunity.com/dev/apikey"

fun HTML.loginPage(chrome: PageChrome, error: String?) {
    page("Sign in", chrome, "page-auth") {
        div("auth-card") {
            img(src = "/static/icon.svg", alt = "", classes = "auth-logo") { width = "64"; height = "64" }
            h1 { +"SteamShelf" }
            p("lead") {
                +"Your Steam library with ProtonDB ratings, HowLongToBeat times and filters that help you pick what to play next."
            }
            if (error != null) p("error-text") { +error }
            a(href = "/auth/steam", classes = "btn primary steam-btn") { +"Sign in through Steam" }
            p("hint") {
                +"You sign in on Steam's own page. Steam only tells SteamShelf which account signed in; your password never reaches it."
            }
        }
    }
}

/** Someone other than the owner signed in through Steam. */
fun HTML.notOwnerPage(chrome: PageChrome) {
    page("Not your shelf", chrome, "page-auth") {
        div("auth-card") {
            img(src = "/static/icon.svg", alt = "", classes = "auth-logo") { width = "64"; height = "64" }
            h1 { +"This SteamShelf belongs to another Steam account" }
            p("lead") {
                +"It shows the library of the account that set it up. To use a different account, sign out of that one in SteamShelf's settings first."
            }
            p("hint") {
                +"If Steam signed you in automatically with the wrong account, sign out on "
                a(href = "https://steamcommunity.com", classes = "inline") {
                    attributes["target"] = "_blank"
                    attributes["rel"] = "noopener noreferrer"
                    +"steamcommunity.com"
                }
                +" and try again."
            }
            a(href = "/auth/steam", classes = "btn") { +"Try another account" }
        }
    }
}

/** Second step after signing in through Steam: the Web API key. */
fun HTML.setupPage(chrome: PageChrome, steamId: String, apiKey: String, language: String, country: String, error: String?) {
    page("Connect", chrome, "page-auth") {
        form(action = "/setup", method = FormMethod.post, classes = "auth-card setup") {
            h1 { +"Connect your library" }
            div("step") {
                span("step-label") { +"STEP 1" }
                h2 { +"Your Steam account" }
                p("ok-text") {
                    icon("check-circle")
                    span { +"Signed in through Steam · SteamID $steamId" }
                }
            }
            div("step") {
                span("step-label") { +"STEP 2" }
                h2 { +"Steam Web API key" }
                p {
                    +"Steam only shares your full library and playtime through its Web API, which needs a free personal key. On the key page, enter any domain name (for example \"localhost\") and copy the key."
                }
                a(href = API_KEY_PAGE, classes = "btn") {
                    attributes["target"] = "_blank"
                    attributes["rel"] = "noopener noreferrer"
                    span { +"Get your API key" }
                    icon("external")
                }
                label("field") {
                    span { +"API key" }
                    input(type = InputType.text, name = "api_key", classes = "mono") {
                        value = apiKey
                        required = true
                        attributes["autocomplete"] = "off"
                        attributes["spellcheck"] = "false"
                        attributes["autocapitalize"] = "off"
                        attributes["pattern"] = "[0-9A-Fa-f]{32}"
                        attributes["title"] = "32 letters and numbers (0–9, A–F)"
                    }
                }
            }
            div("step") {
                span("step-label") { +"STEP 3" }
                h2 { +"Store language" }
                storeFields(language, country)
            }
            if (error != null) p("error-text") { +error }
            button(type = ButtonType.submit, classes = "btn primary") { +"Connect" }
            p("hint") { +"Your key stays on this server and is only sent to api.steampowered.com." }
        }
    }
}

private fun FlowContent.storeFields(language: String, country: String) {
    p("hint") {
        +"Store descriptions and tag names are fetched in this language (English where Steam has no translation). The country decides which store entries are available."
    }
    div("field-row") {
        label("field") {
            span { +"Language" }
            select {
                name = "language"
                STORE_LANGUAGES.forEach { (code, name) ->
                    option {
                        value = code
                        selected = code == language
                        +name
                    }
                }
            }
        }
        label("field narrow") {
            span { +"Country" }
            input(type = InputType.text, name = "country", classes = "mono") {
                value = country
                maxLength = "2"
                required = true
                attributes["pattern"] = "[A-Za-z]{2}"
                attributes["title"] = "Two-letter country code, e.g. US or ES"
                attributes["autocapitalize"] = "characters"
                attributes["spellcheck"] = "false"
            }
        }
    }
}

data class SettingsModel(
    val account: Account,
    val syncPrefs: SyncPrefs,
    val serverPrefs: ServerPrefs,
    val sync: SyncStatus,
    val version: String,
    val now: Long,
    val keyError: String? = null,
)

fun HTML.settingsPage(model: SettingsModel, chrome: PageChrome) {
    page("Settings", chrome, "page-settings") {
        settingsContent(model)
    }
}

/** htmx forms on this page replace the whole #settings block with the one in the response. */
private fun HTMLTag.settingsTarget() {
    attributes["hx-target"] = "#settings"
    attributes["hx-select"] = "#settings"
    attributes["hx-swap"] = "outerHTML"
}

fun FlowContent.settingsContent(model: SettingsModel) {
    val account = model.account
    div("settings") {
        id = "settings"
        a(href = "/", classes = "back-link") {
            attributes["data-back"] = ""
            icon("arrow-left")
            span { +"Library" }
        }
        h1 { +"Settings" }

        section("card") {
            h2 { +"Steam account" }
            div("account-row") {
                account.avatarUrl?.let { img(src = it, alt = "", classes = "avatar large") { width = "56"; height = "56" } }
                div {
                    strong { +account.personaName.ifBlank { "Steam user" } }
                    p("hint") { +"SteamID ${account.steamId}" }
                }
                account.profileUrl?.let {
                    a(href = it, classes = "btn ghost") {
                        attributes["target"] = "_blank"
                        attributes["rel"] = "noopener noreferrer"
                        span { +"View profile" }
                        icon("external")
                    }
                }
            }
            div("setting-line") {
                div {
                    strong { +"Steam Web API key" }
                    p("hint mono") { +"••••••••••••••••••••••••••••${account.apiKey.takeLast(4)}" }
                }
            }
            details("change-key") {
                if (model.keyError != null) attributes["open"] = "open"
                summary("btn") { +"Change API key" }
                form(classes = "inline-form") {
                    attributes["hx-post"] = "/settings/api-key"
                    settingsTarget()
                    label("field") {
                        span { +"New API key" }
                        input(type = InputType.text, name = "api_key", classes = "mono") {
                            required = true
                            attributes["autocomplete"] = "off"
                            attributes["spellcheck"] = "false"
                            attributes["autocapitalize"] = "off"
                        }
                    }
                    if (model.keyError != null) p("error-text") { +model.keyError }
                    div("actions") {
                        button(type = ButtonType.submit, classes = "btn primary") { +"Save" }
                        a(href = API_KEY_PAGE, classes = "btn ghost") {
                            attributes["target"] = "_blank"
                            attributes["rel"] = "noopener noreferrer"
                            span { +"Open the Steam key page" }
                            icon("external")
                        }
                    }
                }
            }
            div("actions") {
                button(type = ButtonType.button, classes = "btn danger") {
                    attributes["hx-post"] = "/settings/sign-out"
                    attributes["hx-confirm"] =
                        "Sign out? This removes your API key and all cached library data from this server, including your game statuses and hand-picked HowLongToBeat matches."
                    +"Sign out"
                }
            }
        }

        section("card") {
            h2 { +"Data sources" }
            form(classes = "switch-list") {
                attributes["hx-post"] = "/settings/sources"
                attributes["hx-trigger"] = "change"
                settingsTarget()
                settingSwitch("ProtonDB ratings", "Fetch Linux/Proton compatibility for every game.", "protondb", model.syncPrefs.autoProtonDb)
                settingSwitch(
                    "HowLongToBeat times",
                    "Look up every game on HowLongToBeat. Big libraries take a few minutes the first time; results are cached for a month.",
                    "hltb",
                    model.syncPrefs.autoHltb,
                )
                settingSwitch("Include free games", "Show free-to-play games you've launched.", "free_games", model.syncPrefs.includeFreeGames)
            }
        }

        section("card") {
            h2 { +"Steam store" }
            form(classes = "store-form") {
                attributes["hx-post"] = "/settings/store"
                settingsTarget()
                storeFields(model.serverPrefs.storeLanguage, model.serverPrefs.storeCountry)
                div("actions") { button(type = ButtonType.submit, classes = "btn primary") { +"Save" } }
            }
        }

        section("card") {
            h2 { +"Sync" }
            p {
                id = "settings-sync-status"
                +when {
                    model.sync.running -> model.sync.label() + "…"
                    model.syncPrefs.lastLibrarySync > 0 -> "Library last synced ${Format.relativeTime(model.syncPrefs.lastLibrarySync, model.now)}"
                    else -> "Not synced yet"
                }
            }
            p("hint") { +"The library syncs by itself every six hours, whether or not this page is open." }
            div("actions") {
                button(type = ButtonType.button, classes = "btn primary") {
                    attributes["hx-post"] = "/sync"
                    attributes["hx-swap"] = "none"
                    +"Sync now"
                }
                button(type = ButtonType.button, classes = "btn") {
                    attributes["hx-post"] = "/sync/refresh"
                    attributes["hx-swap"] = "none"
                    +"Refresh everything"
                }
                button(type = ButtonType.button, classes = "btn") {
                    attributes["hx-post"] = "/sync/clear-cache"
                    attributes["hx-swap"] = "none"
                    attributes["hx-confirm"] = "Clear all cached ratings, times and store data and fetch them again?"
                    +"Clear cache"
                }
            }
            p("hint") {
                +"Refresh re-downloads ratings and times even if they're cached. Your hand-picked HowLongToBeat matches and game statuses are kept."
            }
        }

        section("card") {
            h2 { +"About" }
            p("hint") {
                +"SteamShelf ${model.version}. Library data from the Steam Web API, compatibility ratings from ProtonDB and completion times from HowLongToBeat. Not affiliated with Valve, ProtonDB or HowLongToBeat."
            }
            div("actions") {
                a(href = "https://www.protondb.com", classes = "btn ghost") {
                    attributes["target"] = "_blank"
                    attributes["rel"] = "noopener noreferrer"
                    span { +"ProtonDB" }
                    icon("external")
                }
                a(href = "https://howlongtobeat.com", classes = "btn ghost") {
                    attributes["target"] = "_blank"
                    attributes["rel"] = "noopener noreferrer"
                    span { +"HowLongToBeat" }
                    icon("external")
                }
            }
        }
    }
}

private fun FlowContent.settingSwitch(title: String, hint: String, name: String, on: Boolean) {
    label("switch-row") {
        span("switch-text") {
            strong { +title }
            span("hint") { +hint }
        }
        // Unchecked boxes aren't submitted, so the server reads a missing name as "off".
        input(type = InputType.checkBox, name = name, classes = "switch") {
            value = "1"
            checked = on
            attributes["role"] = "switch"
        }
    }
}

fun HTML.errorPage(chrome: PageChrome, title: String, message: String) {
    page(title, chrome, "page-auth") {
        div("auth-card") {
            h1 { +title }
            p("lead") { +message }
            a(href = "/", classes = "btn primary") { +"Back to the library" }
        }
    }
}
