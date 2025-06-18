package com.example.everytalk.util

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File

object AppImageLoader {

    private var imageLoader: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: newImageLoader(context).also { imageLoader = it }
        }
    }

    private fun newImageLoader(context: Context): ImageLoader {
        val appContext = context.applicationContext
        return ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(appContext, 0.25)
                    .build()
            }
            .diskCache {
                newDiskCache(appContext)
            }
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { newOkHttpClient(appContext) }))
            }
            .build()
    }

    private fun newDiskCache(context: Context): DiskCache {
        return DiskCache.Builder()
            .directory(File(context.cacheDir, "image_cache").toOkioPath())
            .maxSizeBytes(100L * 1024 * 1024) // 100MB
            .build()
    }

    private fun newOkHttpClient(context: Context): OkHttpClient {
        return OkHttpClient.Builder()
            .cache(okhttp3.Cache(File(context.cacheDir, "okhttp_cache"), 10L * 1024 * 1024)) // 10MB
            .build()
    }
}