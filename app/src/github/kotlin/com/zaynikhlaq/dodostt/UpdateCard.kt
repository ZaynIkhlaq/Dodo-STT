package com.zaynikhlaq.dodostt

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Button
import android.widget.TextView

/** The settings screen's Updates row for the GitHub build, which keeps itself current; see [Updater]. */
class UpdateCard(
    private val activity: Activity,
    private val title: TextView,
    private val state: TextView,
    private val action: Button,
) {
    init {
        Updater.schedule(activity)
        action.setOnClickListener {
            if (!Updater.canInstall(activity)) {
                runCatching {
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    )
                }
            } else {
                Updater.checkNow(activity) { if (!activity.isDestroyed) render() }
                render()
            }
        }
    }

    fun render() {
        title.text = activity.getString(R.string.update_version, Updater.installedName(activity))
        val allowed = Updater.canInstall(activity)
        val waiting = Updater.waiting
        val error = Updater.lastError
        state.text = when {
            !allowed -> activity.getString(R.string.update_needs_permission)
            Updater.isChecking -> activity.getString(R.string.update_checking)
            error != null -> activity.getString(R.string.update_failed, error)
            waiting != null -> activity.getString(R.string.update_waiting, waiting)
            Updater.checkedAt > 0 -> activity.getString(
                R.string.update_current,
                DateUtils.getRelativeTimeSpanString(Updater.checkedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
            else -> activity.getString(R.string.update_auto)
        }
        state.setTextColor(activity.getColor(if (!allowed || error != null) R.color.warn else R.color.text_secondary))
        action.setText(if (allowed) R.string.update_check else R.string.update_allow)
        action.isEnabled = !Updater.isChecking
    }
}
