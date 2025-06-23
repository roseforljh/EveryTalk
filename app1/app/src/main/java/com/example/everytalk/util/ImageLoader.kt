package com.example.everytalk.util

import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.Cache
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.TimeUnit

data class ImageLoaderConfig(
    val memoryCachePercent: Double = 0.25,
    val diskCacheSizeBytes: Long = 250L * 1024 * 1024, // 250MB
    val networkCacheSizeBytes: Long = 50L * 1024 * 1024, // 50MB
    val connectTimeoutSeconds: Long = 20,
    val readTimeoutSeconds: Long = 20,
    val writeTimeoutSeconds: Long = 20
)


object AppImageLoader {

    @Volatile
    private var imageLoader: ImageLoader? = null

    fun get(context: Context, config: ImageLoaderConfig = ImageLoaderConfig()): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: newImageLoader(context.applicationContext, config).also { imageLoader = it }
        }
    }


    private fun newImageLoader(context: Context, config: ImageLoaderConfig): ImageLoader {
        return try {
            ImageLoader.Builder(context)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, config.memoryCachePercent)
                        .build()
                }
                .diskCache { newDiskCache(context, config) }
                .components {
                    // Use a custom configured OkHttp client
                    add(OkHttpNetworkFetcherFactory(callFactory = { newOkHttpClient(context, config) }))
                }
                .build()
        } catch (e: Exception) {
            Log.e("AppImageLoader", "Failed to create a full-featured ImageLoader. Falling back to a basic instance.", e)
            // Fallback to a basic loader if initialization fails
            ImageLoader(context)
        }
    }

    private fun newDiskCache(context: Context, config: ImageLoaderConfig): DiskCache? {
        return try {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "image_cache").toOkioPath())
                .maxSizeBytes(config.diskCacheSizeBytes)
                .build()
        } catch (e: Exception) {
            Log.e("AppImageLoader", "Failed to create disk cache. Images will not be cached on disk.", e)
            null // Gracefully degrade: no disk cache
        }
    }

    private fun newOkHttpClient(context: Context, config: ImageLoaderConfig): OkHttpClient {
        return OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir, "okhttp_cache"), config.networkCacheSizeBytes))
            .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "KunTalkwithAi/1.0")
                    .build()
                chain.proceed(request)
            }
            .build()
    }
}