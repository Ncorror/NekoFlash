package ru.forum.adbfastboottool

import android.content.Intent
import android.os.Build
import android.os.Parcelable

/**
 * Reads a parcelable extra without duplicating API-level checks at call sites.
 *
 * Android 13 introduced the type-safe overload. The legacy overload remains
 * necessary for the application's minimum SDK and is isolated here so the rest
 * of the codebase stays warning-free and strongly typed.
 */
internal inline fun <reified T : Parcelable> Intent.parcelableExtra(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name) as? T
    }
