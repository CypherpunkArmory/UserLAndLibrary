package tech.ula.model.repositories

import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.* // ktlint-disable no-wildcard-imports
import tech.ula.BuildConfig
import tech.ula.model.daos.AppsDao
import tech.ula.model.entities.App
import tech.ula.model.remote.GithubAppsFetcher
import tech.ula.utils.*
import tech.ula.utils.preferences.AppsPreferences
import java.util.Locale

class AppsRepository(
    private val appsDao: AppsDao,
    private val remoteAppsSource: GithubAppsFetcher,
    private val appsPreferences: AppsPreferences,
    private val sharedPreferences: SharedPreferences,
    private val logger: Logger = SentryLogger()
) {
    private val className = "AppsRepository"

    private val refreshStatus = MutableLiveData<RefreshStatus>()

    fun getAllApps(): LiveData<List<App>> {
        return appsDao.getAllApps()
    }

    fun getActiveApps(): LiveData<List<App>> {
        return appsDao.getActiveApps()
    }

    fun getRefreshStatus(): LiveData<RefreshStatus> {
        return refreshStatus
    }

    suspend fun refreshData(scope: CoroutineScope) {
        val distributionsList = mutableSetOf<String>()
        refreshStatus.postValue(RefreshStatus.ACTIVE)
        val jobs = mutableListOf<Job>()

        var appsUrl = BuildConfig.DEFAULT_APPS_URL
        if (sharedPreferences.getBoolean("pref_custom_apps_enabled", BuildConfig.DEFAULT_CUSTOM_APPS_ENABLED))
            appsUrl = sharedPreferences.getString("pref_apps", BuildConfig.DEFAULT_APPS_URL)!!
        var flush = false
        if (sharedPreferences.contains("prev_pref_apps_repo")) {
            if (!appsUrl.equals(sharedPreferences.getString("prev_pref_apps_repo", BuildConfig.DEFAULT_APPS_URL))) {
                flush = true
            }
        }
        with(sharedPreferences.edit()) {
            putString("prev_pref_apps_repo", appsUrl)
            apply()
        }
        if (flush) {
            appsDao.deleteAllApps()
        }

        try {
            remoteAppsSource.fetchAppsList().forEach { app ->
                jobs.add(scope.launch {
                    if (app.category.toLowerCase(Locale.ENGLISH) == "distribution") distributionsList.add(app.name)
                    remoteAppsSource.fetchAppIcon(app)
                    remoteAppsSource.fetchAppDescription(app)
                    remoteAppsSource.fetchAppScript(app)
                    appsDao.insertApp(app) // Insert the db element last to force observer refresh
            }) }
        } catch (err: Exception) {
            refreshStatus.postValue(RefreshStatus.FAILED)
            val message = err.message ?: "Not found"
            val breadcrumb = UlaBreadcrumb(className, BreadcrumbType.RuntimeError, message)
            logger.addBreadcrumb(breadcrumb)
            logger.sendEvent("App Refresh Failed")
            return
        }
        jobs.joinAll()
        refreshStatus.postValue(RefreshStatus.FINISHED)
        appsPreferences.setDistributionsList(distributionsList)
    }
}

enum class RefreshStatus {
    ACTIVE, FINISHED, FAILED, INACTIVE
}