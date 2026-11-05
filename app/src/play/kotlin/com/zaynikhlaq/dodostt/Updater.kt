package com.zaynikhlaq.dodostt

import android.content.Context

/**
 * Google Play updates this build, and Play doesn't allow an app to install updates of its own, so
 * the GitHub build's self-updater is left out entirely. These are the two calls the shared code makes.
 */
object Updater {
    fun schedule(c: Context) = Unit

    fun setDictating(c: Context, busy: Boolean) = Unit
}
