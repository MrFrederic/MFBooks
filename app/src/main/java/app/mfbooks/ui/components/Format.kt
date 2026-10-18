package app.mfbooks.ui.components

/** 1:02:03 / 2:03 */
fun formatClock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** "3 h 12 m" / "12 m" */
fun formatHm(ms: Long): String {
    val m = ms.coerceAtLeast(0) / 60000
    return if (m >= 60) "${m / 60} h ${m % 60} m" else "$m m"
}
