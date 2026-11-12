package dev.steamshelf.ui.common

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.icu.text.CompactDecimalFormat
import android.text.format.DateUtils
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import dev.steamshelf.R
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

fun formatHours(res: Resources, hours: Double): String = when {
    hours <= 0.0 -> res.getString(R.string.unit_hours, "0")
    hours < 1.0 -> res.getString(R.string.unit_minutes, (hours * 60).roundToInt().coerceAtLeast(1).toString())
    hours < 10.0 -> res.getString(R.string.unit_hours, String.format(Locale.getDefault(), "%.1f", hours))
    else -> res.getString(R.string.unit_hours, NumberFormat.getIntegerInstance().format(hours.roundToInt()))
}

fun formatMinutes(res: Resources, minutes: Int): String =
    if (minutes == 0) res.getString(R.string.never_played) else formatHours(res, minutes / 60.0)

fun formatSeconds(res: Resources, seconds: Long): String? = seconds.takeIf { it > 0 }?.let { formatHours(res, it / 3600.0) }

/** "1.2K" style counts, in the device's locale. */
fun formatCount(count: Int): String =
    CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT).format(count)

fun relativeDate(res: Resources, epochSeconds: Long): String =
    if (epochSeconds <= 0) res.getString(R.string.date_never)
    else DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1000, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS,
    ).toString()

/** Short relative date that fits a stat tile: "Today", "3 d ago", "5 wk ago", "4 mo ago", "2 y ago". */
fun shortRelativeDate(res: Resources, epochSeconds: Long): String {
    if (epochSeconds <= 0) return res.getString(R.string.date_never)
    val days = ((System.currentTimeMillis() / 1000 - epochSeconds) / 86_400).coerceAtLeast(0)
    return when {
        days == 0L -> res.getString(R.string.date_today)
        days == 1L -> res.getString(R.string.date_yesterday)
        days < 14 -> res.getString(R.string.date_days_ago, days)
        days < 60 -> res.getString(R.string.date_weeks_ago, days / 7)
        days < 365 -> res.getString(R.string.date_months_ago, days / 30)
        else -> res.getString(R.string.date_years_ago, days / 365)
    }
}

fun formatDate(epochSeconds: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()))

/** Custom Tab for web pages; [externalApp] lets e.g. the Steam app handle store links. */
fun openUrl(context: Context, url: String, externalApp: Boolean = false) {
    val uri = url.toUri()
    if (!externalApp) {
        val opened = runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
        }.isSuccess
        if (opened) return
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        Toast.makeText(context, R.string.error_no_app_for_link, Toast.LENGTH_SHORT).show()
    }
}

fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
