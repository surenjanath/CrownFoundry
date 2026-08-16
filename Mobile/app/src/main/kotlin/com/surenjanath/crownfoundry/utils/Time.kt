package com.surenjanath.crownfoundry.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

/** "3 hours ago", the way the website words it. [seconds] is a unix timestamp. */
fun formatAsRelativeTime(seconds: Long?): String {
    if (seconds == null || seconds <= 0) return ""

    val elapsed = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(seconds)

    if (elapsed < 0) return "just now"

    val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
    if (minutes < 1) return "just now"
    if (minutes < 60) return "$minutes ${"minute".pluralized(minutes)} ago"

    val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
    if (hours < 24) return "$hours ${"hour".pluralized(hours)} ago"

    val days = TimeUnit.MILLISECONDS.toDays(elapsed)
    if (days < 31) return "$days ${"day".pluralized(days)} ago"

    val months = days / 30
    if (months < 12) return "$months ${"month".pluralized(months)} ago"

    val years = days / 365
    return "$years ${"year".pluralized(years)} ago"
}

fun formatAsDate(seconds: Long?): String =
    if (seconds == null || seconds <= 0) "" else dateFormat.format(Date(TimeUnit.SECONDS.toMillis(seconds)))

private fun String.pluralized(count: Long) = if (count == 1L) this else "${this}s"
