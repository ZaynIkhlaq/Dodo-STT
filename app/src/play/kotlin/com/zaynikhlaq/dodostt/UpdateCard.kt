package com.zaynikhlaq.dodostt

import android.app.Activity
import android.view.View
import android.widget.Button
import android.widget.TextView

/** The settings screen's Updates row for the Play build: Google Play does the updating, so it only reports the version. */
class UpdateCard(
    private val activity: Activity,
    private val title: TextView,
    private val state: TextView,
    action: Button,
) {
    init {
        action.visibility = View.GONE
    }

    fun render() {
        title.text = activity.getString(
            R.string.update_version,
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty(),
        )
        state.setText(R.string.update_play)
    }
}
