package com.gazw89.goodhive.di

import android.content.Context
import androidx.room.Room
import com.gazw89.goodhive.data.db.AppDatabase
import com.gazw89.goodhive.data.db.CommandLogDao
import com.gazw89.goodhive.data.db.DatabaseMigrations
import com.gazw89.goodhive.data.db.HeatingZoneCacheDao
import com.gazw89.goodhive.data.db.HistoryDao
import com.gazw89.goodhive.data.db.ZoneStateDao
import com.gazw89.goodhive.data.db.PendingCommandDao
import com.gazw89.goodhive.data.db.RoomOrderDao
import com.gazw89.goodhive.data.network.HiveApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "goodhive.db")
            .addMigrations(*DatabaseMigrations.ALL)
            .build()

    @Provides
    fun provideHeatingZoneCacheDao(db: AppDatabase): HeatingZoneCacheDao = db.heatingZoneCacheDao()

    @Provides
    fun providePendingCommandDao(db: AppDatabase): PendingCommandDao = db.pendingCommandDao()

    @Provides
    fun provideCommandLogDao(db: AppDatabase): CommandLogDao = db.commandLogDao()

    @Provides
    fun provideRoomOrderDao(db: AppDatabase): RoomOrderDao = db.roomOrderDao()

    @Provides
    fun provideHistoryDao(db: AppDatabase): HistoryDao = db.historyDao()

    @Provides
    fun provideZoneStateDao(db: AppDatabase): ZoneStateDao = db.zoneStateDao()
    @Provides
    @Singleton
    fun provideHiveApiService(okHttpClient: OkHttpClient, json: Json): HiveApiService {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl("https://beekeeper.hivehome.com/1.0/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(HiveApiService::class.java)
    }
}
