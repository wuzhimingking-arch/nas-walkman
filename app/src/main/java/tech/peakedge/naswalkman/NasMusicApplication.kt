package tech.peakedge.naswalkman

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import tech.peakedge.naswalkman.data.db.AppDatabase
import tech.peakedge.naswalkman.data.repository.MusicRepository
import tech.peakedge.naswalkman.data.repository.SettingsStore
import tech.peakedge.naswalkman.network.BasicAuthInterceptor
import tech.peakedge.naswalkman.network.DefaultConnectionResolver
import tech.peakedge.naswalkman.network.DatabaseCredentialsProvider
import tech.peakedge.naswalkman.network.DigestAuthenticator
import tech.peakedge.naswalkman.network.FnConnectClient
import tech.peakedge.naswalkman.network.InMemoryCookieJar
import tech.peakedge.naswalkman.network.WebDavClient
import tech.peakedge.naswalkman.network.WebDavNasFileClient
import tech.peakedge.naswalkman.security.CredentialCipher
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
        "nas_walkman.db",
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()
    private val credentialCipher = CredentialCipher()
    private val credentialsProvider = DatabaseCredentialsProvider(database.nasDao(), credentialCipher)
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .cookieJar(InMemoryCookieJar())
        .addInterceptor(BasicAuthInterceptor(credentialsProvider))
        .authenticator(DigestAuthenticator(credentialsProvider))
        .build()
    private val settingsStore = SettingsStore(appContext)
    val repository: MusicRepository = MusicRepository(
        context = appContext,
        database = database,
        credentialCipher = credentialCipher,
        connectionResolver = DefaultConnectionResolver(),
        fnConnectClient = FnConnectClient(okHttpClient),
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

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE nas_servers ADD COLUMN selectedMusicRemotePath TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE nas_servers ADD COLUMN selectedMusicDisplayPath TEXT NOT NULL DEFAULT '已手动填写路径'")
        db.execSQL("ALTER TABLE nas_servers ADD COLUMN selectedMusicFolderName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE nas_servers ADD COLUMN selectedMusicSelectedAt INTEGER")
        db.execSQL(
            """
            UPDATE nas_servers
            SET selectedMusicRemotePath = musicRootPath,
                selectedMusicFolderName = musicRootPath,
                selectedMusicDisplayPath = '已手动填写路径'
            WHERE selectedMusicRemotePath = ''
            """.trimIndent(),
        )
    }
}
