package com.fnnas.music

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fnnas.music.data.db.AppDatabase
import com.fnnas.music.data.repository.MusicRepository
import com.fnnas.music.data.repository.SettingsStore
import com.fnnas.music.network.BasicAuthInterceptor
import com.fnnas.music.network.DefaultConnectionResolver
import com.fnnas.music.network.DatabaseCredentialsProvider
import com.fnnas.music.network.DigestAuthenticator
import com.fnnas.music.network.WebDavClient
import com.fnnas.music.network.WebDavNasFileClient
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
    )
        .addMigrations(MIGRATION_1_2)
        .build()
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
        connectionResolver = DefaultConnectionResolver(),
        nasFileClient = WebDavNasFileClient(WebDavClient(okHttpClient)),
        settingsStore = settingsStore,
    )
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE nas_servers ADD COLUMN mode TEXT NOT NULL DEFAULT 'WEBDAV_ADVANCED'")
        db.execSQL("ALTER TABLE nas_servers ADD COLUMN inputAddress TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE nas_servers ADD COLUMN resolvedBaseUrl TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            UPDATE nas_servers
            SET inputAddress = baseUrl,
                resolvedBaseUrl = baseUrl
            WHERE inputAddress = '' OR resolvedBaseUrl = ''
            """.trimIndent(),
        )
    }
}
