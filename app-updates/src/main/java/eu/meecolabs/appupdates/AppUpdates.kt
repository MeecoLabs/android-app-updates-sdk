package eu.meecolabs.appupdates

import android.content.pm.PackageManager
import eu.meecolabs.appupdates.models.AppUpdate
import eu.meecolabs.appupdates.models.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.fdroid.CompatibilityCheckerImpl
import org.fdroid.UpdateChecker
import org.fdroid.index.IndexParser
import org.fdroid.index.parseEntry
import org.fdroid.index.v2.EntryVerifier
import org.fdroid.index.v2.IndexV2FullStreamProcessor
import org.fdroid.index.v2.IndexV2StreamReceiver
import org.fdroid.index.v2.PackageV2
import org.fdroid.index.v2.RepoV2
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant


class AppUpdates(
    private val repoDir: File,
    private val repo: Repo,
    packageName: String,
    packageManager: PackageManager,
    private val isDebug: Boolean = false
) {
    companion object {
        private const val ENTRY_JAR_FILENAME = "entry.jar"
    }

    private val httpClient = OkHttpClient.Builder()
        .authenticator { _, response ->
            if (repo.username != null && repo.password != null) {
                val credentials = Credentials.basic(repo.username, repo.password)
                response.request.newBuilder().header("Authorization", credentials).build()
            } else {
                response.request
            }
        }
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (isDebug) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE

            redactHeader("Authorization")
            redactHeader("Cookie")
        })
        .build()

    private val packageInfo = packageManager.getPackageInfo(packageName, 0)

    private val updateChecker = UpdateChecker(CompatibilityCheckerImpl(packageManager))

    suspend fun checkForUpdates(
        releaseChannels: List<String>? = null
    ): AppUpdate? = withContext(Dispatchers.IO) {
        val entryJarRequest = Request.Builder()
            .get()
            .url(repo.url.toHttpUrl().newBuilder().addPathSegment(ENTRY_JAR_FILENAME).build())
            .build()
        val entryJarResponse = httpClient.newCall(entryJarRequest).execute()
        if (!entryJarResponse.isSuccessful) {
            throw Exception("Could not load update repository entry.")
        }

        val entryJarFile = File(repoDir, ENTRY_JAR_FILENAME)
        Files.copy(entryJarResponse.body.byteStream(), entryJarFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        val entryVerifier = EntryVerifier(entryJarFile, null, repo.fingerprint.lowercase())
        val (_, entry) = entryVerifier.getStreamAndVerify { IndexParser.parseEntry(it) }

        val indexFileRequest = Request.Builder()
            .get()
            .url(repo.url.toHttpUrl().newBuilder().addPathSegment(entry.index.name).build())
            .build()
        val indexFileResponse = httpClient.newCall(indexFileRequest).execute()
        if (!indexFileResponse.isSuccessful) {
            throw Exception("Could not load update repository index.")
        }

        val streamReceiver = object : IndexV2StreamReceiver {
            var info: PackageV2? = null
                private set

            override fun receive(repo: RepoV2, version: Long) {
                // Ignore
            }

            override fun receive(packageName: String, p: PackageV2) {
                if (packageName == packageInfo.packageName) {
                    info = p
                }
            }

            override fun onStreamEnded() {
                // Ignore
            }
        }

        val streamProcessor = IndexV2FullStreamProcessor(streamReceiver)

        val inputStream = DigestInputStream(indexFileResponse.body.byteStream(), MessageDigest.getInstance("SHA-256"))
        inputStream.use {
            streamProcessor.process(entry.version, it) { }
        }

        if (!inputStream.messageDigest.digest().toHexString().equals(entry.index.sha256, ignoreCase = true)) {
            throw Exception("Repository integrity could not be verified.")
        }

        val versions = streamReceiver.info?.versions?.values?.toList()
            ?: throw Exception("Could not find app in repository.")

        val update = updateChecker.getUpdate(
            versions = versions,
            packageInfo = packageInfo,
            releaseChannels = releaseChannels
        )
            ?: return@withContext null
        return@withContext AppUpdate(
            versionCode = update.versionCode,
            versionName = update.versionName,
            releaseTimestamp = Instant.ofEpochMilli(update.added),
            whatsNew = update.whatsNew["en-US"] ?: update.whatsNew["en-GB"] ?: update.whatsNew["en"] ?: update.whatsNew.values.firstOrNull()
        )
    }
}
