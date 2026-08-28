package eu.meecolabs.appupdates.models

data class Repo(
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val fingerprint: String
)
