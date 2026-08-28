package eu.meecolabs.appupdates.models

import java.time.Instant

data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val releaseTimestamp: Instant,
    val whatsNew: String?
)
