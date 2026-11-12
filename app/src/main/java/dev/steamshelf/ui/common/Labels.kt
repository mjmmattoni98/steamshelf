package dev.steamshelf.ui.common

import android.content.res.Resources
import androidx.annotation.StringRes
import dev.steamshelf.R
import dev.steamshelf.model.DeckCompat
import dev.steamshelf.model.DeckFilter
import dev.steamshelf.model.LengthFilter
import dev.steamshelf.model.PlayStateFilter
import dev.steamshelf.model.PlayStatus
import dev.steamshelf.model.PlayerModeFilter
import dev.steamshelf.model.ProtonFilter
import dev.steamshelf.model.ProtonTier
import dev.steamshelf.model.SortField
import dev.steamshelf.model.SyncPhase
import dev.steamshelf.model.SyncProblem

// The model package stays free of Android; its enums get their user-facing text here.

@StringRes
fun SyncPhase.labelRes(): Int = when (this) {
    SyncPhase.LIBRARY -> R.string.sync_phase_library
    SyncPhase.STORE -> R.string.sync_phase_store
    SyncPhase.PROTONDB -> R.string.sync_phase_protondb
    SyncPhase.HLTB -> R.string.sync_phase_hltb
}

@StringRes
fun PlayStatus.labelRes(): Int = when (this) {
    PlayStatus.FINISHED -> R.string.status_finished
    PlayStatus.DROPPED -> R.string.status_dropped
    PlayStatus.HIDDEN -> R.string.status_hidden
}

@StringRes
fun DeckCompat.labelRes(): Int = when (this) {
    DeckCompat.UNKNOWN -> R.string.deck_unknown
    DeckCompat.UNSUPPORTED -> R.string.deck_unsupported
    DeckCompat.PLAYABLE -> R.string.deck_playable
    DeckCompat.VERIFIED -> R.string.deck_verified
}

@StringRes
fun ProtonTier.labelRes(): Int = when (this) {
    ProtonTier.PLATINUM -> R.string.tier_platinum
    ProtonTier.GOLD -> R.string.tier_gold
    ProtonTier.SILVER -> R.string.tier_silver
    ProtonTier.BRONZE -> R.string.tier_bronze
    ProtonTier.BORKED -> R.string.tier_borked
    ProtonTier.PENDING -> R.string.tier_pending
}

@StringRes
fun PlayStateFilter.labelRes(): Int = when (this) {
    PlayStateFilter.UNPLAYED -> R.string.play_state_unplayed
    PlayStateFilter.TRIED -> R.string.play_state_tried
    PlayStateFilter.IN_PROGRESS -> R.string.play_state_in_progress
    PlayStateFilter.BEATEN -> R.string.play_state_beaten
    PlayStateFilter.RECENT -> R.string.play_state_recent
    PlayStateFilter.DROPPED -> R.string.status_dropped
}

@StringRes
fun ProtonFilter.labelRes(): Int = when (this) {
    ProtonFilter.NATIVE -> R.string.proton_filter_native
    ProtonFilter.PLATINUM -> R.string.tier_platinum
    ProtonFilter.GOLD -> R.string.tier_gold
    ProtonFilter.SILVER -> R.string.tier_silver
    ProtonFilter.BRONZE -> R.string.tier_bronze
    ProtonFilter.BORKED -> R.string.tier_borked
    ProtonFilter.NO_REPORTS -> R.string.proton_filter_no_reports
}

@StringRes
fun DeckFilter.labelRes(): Int = compat.labelRes()

@StringRes
fun LengthFilter.labelRes(): Int = when (this) {
    LengthFilter.SHORT -> R.string.length_short
    LengthFilter.MEDIUM -> R.string.length_medium
    LengthFilter.LONG -> R.string.length_long
    LengthFilter.VERY_LONG -> R.string.length_very_long
    LengthFilter.UNKNOWN -> R.string.length_unknown
}

@StringRes
fun PlayerModeFilter.labelRes(): Int = when (this) {
    PlayerModeFilter.SINGLE -> R.string.players_single
    PlayerModeFilter.COOP -> R.string.players_coop
    PlayerModeFilter.MULTIPLAYER -> R.string.players_multiplayer
    PlayerModeFilter.SPLIT_SCREEN -> R.string.players_split_screen
}

@StringRes
fun SortField.labelRes(): Int = when (this) {
    SortField.NAME -> R.string.sort_name
    SortField.PLAYTIME -> R.string.sort_playtime
    SortField.LAST_PLAYED -> R.string.sort_last_played
    SortField.TIME_TO_BEAT -> R.string.sort_time_to_beat
    SortField.TIME_LEFT -> R.string.sort_time_left
    SortField.PROTONDB -> R.string.sort_protondb
    SortField.REVIEWS -> R.string.sort_reviews
    SortField.RELEASE -> R.string.sort_release
}

fun SyncProblem.message(res: Resources): String = when (this) {
    is SyncProblem.LibraryNotRefreshed -> res.getString(R.string.sync_error_library_not_refreshed, cause.userMessage(res))
    SyncProblem.StoreIncomplete -> res.getString(R.string.sync_error_store_incomplete)
    is SyncProblem.ProtonIncomplete -> res.getQuantityString(R.plurals.sync_error_proton_incomplete, failedGames, failedGames)
    SyncProblem.HltbUnavailable -> res.getString(R.string.sync_error_hltb_unavailable)
    is SyncProblem.Failed -> res.getString(R.string.sync_error_failed, cause.userMessage(res))
}
