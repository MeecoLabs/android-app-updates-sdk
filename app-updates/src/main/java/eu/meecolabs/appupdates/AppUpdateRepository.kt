package eu.meecolabs.appupdates

import android.content.Context
import android.content.Intent
import android.net.Uri
import eu.meecolabs.appupdates.models.AppUpdate
import eu.meecolabs.appupdates.models.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

class AppUpdateRepository(
    repoDir: File,
    private val repo: Repo,
    context: Context,
    isDebug: Boolean
) {
    sealed interface State {
        data object Idle : State

        data object Checking : State

        data class Failure(
            val error: Exception
        ) : State

        data class NoUpdatesAvailable(
            val lastChecked: Instant
        ) : State

        data class UpdatesAvailable(
            val lastChecked: Instant,
            val update: AppUpdate
        ) : State
    }

    private val packageName = context.packageName
    private val packageManager = context.packageManager

    private val appUpdates = AppUpdates(repoDir, repo, packageName, packageManager, isDebug)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    suspend fun checkForUpdates() = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        _state.value = try {
            val now = Instant.now()
            val update = appUpdates.checkForUpdates()
            if (update == null) {
                State.NoUpdatesAvailable(now)
            } else {
                State.UpdatesAvailable(now, update)
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            State.Failure(ex)
        }
    }

    val fDroidReposUrl: Uri
        get() {
            val repoUrl = Uri.parse(repo.url)
            val userInfo = arrayOf(repo.username, repo.password).filterNotNull().joinToString(":")
            return repoUrl
                .buildUpon()
                .scheme("fdroidrepos")
                .authority(arrayOf(userInfo, repoUrl.authority).joinToString("@"))
                .appendQueryParameter("fingerprint", repo.fingerprint)
                .build()
        }

    val fDroidReposIntent = Intent(Intent.ACTION_VIEW, fDroidReposUrl)

    val fDroidPackageDetailsIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/$packageName"))

    val canResolveFDroidReposIntent: Boolean
        get() = packageManager.resolveActivity(fDroidReposIntent, 0) != null
}
