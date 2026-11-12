package dev.steamshelf.ui.common

import android.content.res.Resources
import androidx.annotation.StringRes
import dev.steamshelf.R
import dev.steamshelf.data.hltb.HltbRateLimitedException
import dev.steamshelf.data.net.HttpStatusException
import dev.steamshelf.data.steam.SteamApiException
import dev.steamshelf.data.steam.SteamApiException.Reason
import java.io.IOException

/** Text a ViewModel wants shown; it has no Context, so the UI resolves it against resources. */
sealed interface UiText {
    fun resolve(res: Resources): String

    class Res(@param:StringRes val id: Int, vararg val args: Any) : UiText {
        override fun resolve(res: Resources): String =
            res.getString(id, *args.map { if (it is UiText) it.resolve(res) else it }.toTypedArray())
    }

    data class Error(val cause: Throwable) : UiText {
        override fun resolve(res: Resources): String = cause.userMessage(res)
    }
}

/** A failure explained in the user's language. */
fun Throwable.userMessage(res: Resources): String = when (this) {
    is SteamApiException -> when (reason) {
        Reason.NO_GAMES -> res.getString(R.string.error_steam_no_games)
        Reason.KEY_REJECTED -> res.getString(R.string.error_steam_key_rejected, detail.orEmpty())
        Reason.RATE_LIMITED -> res.getString(R.string.error_steam_rate_limited)
        Reason.HTTP_ERROR -> res.getString(R.string.error_steam_http, detail.orEmpty())
        Reason.PROFILE_NOT_FOUND -> res.getString(R.string.error_steam_profile_not_found, detail.orEmpty())
        Reason.VANITY_NOT_FOUND -> res.getString(R.string.error_steam_vanity_not_found, detail.orEmpty())
        Reason.SIGN_IN_INVALID -> res.getString(R.string.error_sign_in_invalid)
        Reason.SIGN_IN_CANCELLED -> res.getString(R.string.error_sign_in_cancelled)
        Reason.SIGN_IN_UNEXPECTED -> res.getString(R.string.error_sign_in_unexpected)
        Reason.SIGN_IN_NO_STEAM_ID -> res.getString(R.string.error_sign_in_no_steam_id)
        Reason.SIGN_IN_NOT_VERIFIED -> res.getString(R.string.error_sign_in_not_verified)
    }
    is HltbRateLimitedException -> res.getString(R.string.error_hltb_rate_limited)
    is HttpStatusException -> res.getString(R.string.error_http, code)
    is IOException -> res.getString(R.string.error_network)
    else -> res.getString(R.string.error_generic)
}
