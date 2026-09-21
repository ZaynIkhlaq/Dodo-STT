package com.zaynikhlaq.dodostt

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The hourly background check; see [Updater]. */
class UpdateJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Updater.checkNow(this) { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

/** Receives PackageInstaller's verdict on an update. Not exported: only our own session reports here. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Updater.ACTION_STATUS) Updater.onInstallStatus(context, intent)
    }
}

/** Re-registers the background check after an update, in case the platform dropped it. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.schedule(context)
    }
}
