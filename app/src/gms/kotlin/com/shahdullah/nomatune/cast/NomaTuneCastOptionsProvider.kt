/*
 * NomaTune (2026)
 * © Shahdullah — github.com/Shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.shahdullah.nomatune.R

class NomaTuneCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val receiverApplicationId =
            context
                .getString(R.string.cast_receiver_application_id)
                .takeIf(String::isNotBlank)
                ?: CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

        return CastOptions
            .Builder()
            .setReceiverApplicationId(receiverApplicationId)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
