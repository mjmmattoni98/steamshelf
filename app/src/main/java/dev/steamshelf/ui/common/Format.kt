package dev.steamshelf.ui.common

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

fun formatHours(hours: Double): String = when {
    hours <= 0.0 -> "0 h"
    hours < 1.0 -> "${(hours * 60).roundToInt().coerceAtLeast(1)} min"
    hours < 10.0 -> String.format(Locale.getDefault(), "%.1f h", hours)
    else -> "${NumberFormat.getIntegerInstance().format(hours.roundToInt())} h"
}

fun formatMinutes(minutes: Int): String = if (minutes == 0) "Never played" else formatHours(minutes / 60.0)

fun formatSeconds(seconds: Long): String? = seconds.takeIf { it > 0 }?.let { formatHours(it / 3600.0) }

fun formatCount(count: Int): String = when {
    count >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", count / 1_000_000.0)
    count >= 10_000 -> "${count / 1000}k"
    count >= 1_000 -> String.format(Locale.getDefault(), "%.1fk", count / 1000.0)
    else -> count.toString()
}

fun relativeDate(epochSeconds: Long): String =
    if (epochSeconds <= 0) "Never"
    else DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1000, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS,
    ).toString()

/** Short relative date that fits a stat tile: "Today", "3 d ago", "5 wk ago", "4 mo ago", "2 y ago". */
fun shortRelativeDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "Never"
    val days = ((System.currentTimeMillis() / 1000 - epochSeconds) / 86_400).coerceAtLeast(0)
    return when {
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        days < 14 -> "$days d ago"
        days < 60 -> "${days / 7} wk ago"
        days < 365 -> "${days / 30} mo ago"
        else -> "${days / 365} y ago"
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
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
