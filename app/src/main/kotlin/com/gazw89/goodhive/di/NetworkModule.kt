package com.gazw89.goodhive.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            // BASIC, not BODY/HEADERS — the Authorization header carries a live session
            // token and request bodies can carry the home address; never log those.
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            // Hive's beekeeper CDN started requiring a Hive-like User-Agent; bare okhttp
            // gets intermittent 403s (same fix Home Assistant/apyhiveapi needed).
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Hive/12.04.0 Android/GoodHive")
                    .header("Accept", "*/*")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()
    }
}
