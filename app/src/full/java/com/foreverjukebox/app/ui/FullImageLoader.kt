package com.foreverjukebox.app.ui

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.foreverjukebox.app.net.CleartextGuardInterceptor
import okhttp3.OkHttpClient

/**
 * Builds Coil's image loader with the same [CleartextGuardInterceptor] the API and Cast clients
 * use, so remote image loads honour the app-layer cleartext policy. Full-flavor release builds
 * enable usesCleartextTraffic platform-wide (see app/build.gradle.kts); without this, Coil's
 * default OkHttp client would permit cleartext http image requests to public hosts, punching a
 * hole in the "no cleartext to the public internet" guarantee.
 */
fun guardedImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(
                OkHttpNetworkFetcherFactory(
                    callFactory = {
                        OkHttpClient.Builder()
                            .addNetworkInterceptor(CleartextGuardInterceptor)
                            .build()
                    }
                )
            )
        }
        .build()
