package fr.reunion.enregistreur.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 75_000 -> "01:15", 3_725_000 -> "1:02:05" */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Horodatage d'un segment : toujours hh:mm:ss pour un alignement régulier. */
fun formatTimestamp(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
}

private val dateFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'à' HH'h'mm", Locale.FRENCH)
private val shortDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH'h'mm", Locale.FRENCH)
private val dayFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH)

fun formatDateLong(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    dateFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone)).replaceFirstChar { it.uppercase() }

fun formatDateShort(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    shortDateFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))

fun formatDay(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    dayFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** Estimation lisible d'un temps restant. */
fun formatRemaining(seconds: Long): String = when {
    seconds < 60 -> "moins d'une minute"
    seconds < 3600 -> "environ ${(seconds + 30) / 60} min"
    else -> "environ ${seconds / 3600} h ${"%02d".format((seconds % 3600) / 60)}"
}

/** Nom de fichier sûr à partir d'un titre. */
fun safeFileName(title: String): String {
    val base = title.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").replace(Regex("\\s+"), " ")
    return base.take(80).ifBlank { "Reunion" }
}
