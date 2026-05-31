package com.fnnas.music

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.fnnas.music.data.db.AppDatabase
import com.fnnas.music.data.repository.MusicRepository
import com.fnnas.music.data.repository.SettingsStore
import com.fnnas.music.network.BasicAuthInterceptor
import com.fnnas.music.network.DatabaseCredentialsProvider
import com.fnnas.music.network.DigestAuthenticator
import com.fnnas.music.network.WebDavClient
import com.fnnas.music.security.CredentialCipher
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class NasMusicApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "fn_nas_music.db",
    ).build()
    private val credentialCipher = CredentialCipher()
    private val credentialsProvider = DatabaseCredentialsProvider(database.nasDao(), credentialCipher)
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .addInterceptor(BasicAuthInterceptor(credentialsProvider))
        .authenticator(DigestAuthenticator(credentialsProvider))
        .build()
    private val settingsStore = SettingsStore(appContext)
    val repository: MusicRepository = MusicRepository(
        context = appContext,
        database = database,
        credentialCipher = credentialCipher,
        webDavClient = WebDavClient(okHttpClient),
        settingsStore = settingsStore,
    )
}
