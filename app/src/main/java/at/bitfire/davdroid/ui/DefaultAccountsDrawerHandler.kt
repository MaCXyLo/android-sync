/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.davdroid.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import at.bitfire.davdroid.App
import at.bitfire.davdroid.BuildConfig
import at.bitfire.davdroid.R

class DefaultAccountsDrawerHandler: IAccountsDrawerHandler {

    companion object {
        private const val BETA_FEEDBACK_URI = "mailto:support@davx5.com?subject=${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME} feedback (${BuildConfig.VERSION_CODE})"
    }


    override fun initMenu(context: Context, menu: Menu) {
        if (BuildConfig.VERSION_NAME.contains("-beta") || BuildConfig.VERSION_NAME.contains("-rc"))
            menu.findItem(R.id.nav_beta_feedback).isVisible = true
    }

    override fun onNavigationItemSelected(activity: Activity, item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_about ->
                activity.startActivity(Intent(activity, AboutActivity::class.java))
            R.id.nav_app_settings ->
                activity.startActivity(Intent(activity, AppSettingsActivity::class.java))
            R.id.nav_beta_feedback ->
                UiUtils.launchUri(activity, Uri.parse(BETA_FEEDBACK_URI), Intent.ACTION_SENDTO)
            R.id.nav_website ->
                activity.startActivity(Intent(Intent.ACTION_VIEW,  Uri.parse("https://www.infomaniak.com")))
            R.id.nav_faq ->
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://faq.infomaniak.com/2302")))
            R.id.nav_manual ->
                activity.startActivity(Intent(Intent.ACTION_VIEW, App.homepageUrl(activity)
                        .buildUpon().appendEncodedPath("manual/").build()))
            else ->
                return false
        }

        return true
    }

}
