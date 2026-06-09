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
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .build()
    private val credentialCipher = CredentialCipher()
    private val credentialsProvider = DatabaseCredentialsProvider(database.nasDao(), credentialCipher)
    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
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

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS music_folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sourceType TEXT NOT NULL,
                sourceKey TEXT NOT NULL,
                path TEXT NOT NULL,
                displayName TEXT NOT NULL,
                includeSubfolders INTEGER NOT NULL DEFAULT 1,
                nasServerId INTEGER,
                songCount INTEGER,
                lastScannedAt INTEGER,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_music_folders_sourceKey ON music_folders(sourceKey)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_music_folders_sourceType ON music_folders(sourceType)")
        db.execSQL("ALTER TABLE tracks ADD COLUMN sourceType TEXT NOT NULL DEFAULT 'NAS'")
        db.execSQL("ALTER TABLE tracks ADD COLUMN sourceFolderId INTEGER")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_sourceFolderId ON tracks(sourceFolderId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_sourceType ON tracks(sourceType)")
        db.execSQL(
            """
            INSERT OR IGNORE INTO music_folders (
                sourceType,
                sourceKey,
                path,
                displayName,
                includeSubfolders,
                nasServerId,
                songCount,
                lastScannedAt,
                createdAt,
                updatedAt
            )
            SELECT
                'NAS',
                'NAS:' || id || ':' || CASE
                    WHEN selectedMusicRemotePath != '' THEN selectedMusicRemotePath
                    ELSE musicRootPath
                END,
                CASE
                    WHEN selectedMusicRemotePath != '' THEN selectedMusicRemotePath
                    ELSE musicRootPath
                END,
                CASE
                    WHEN selectedMusicDisplayPath != '' THEN selectedMusicDisplayPath
                    WHEN selectedMusicRemotePath != '' THEN selectedMusicRemotePath
                    ELSE musicRootPath
                END,
                1,
                id,
                NULL,
                NULL,
                createdAt,
                updatedAt
            FROM nas_servers
            """.trimIndent(),
        )
        db.execSQL(
            """
            UPDATE tracks
            SET sourceFolderId = (
                SELECT id
                FROM music_folders
                WHERE music_folders.sourceType = 'NAS'
                ORDER BY music_folders.createdAt
                LIMIT 1
            )
            WHERE sourceFolderId IS NULL
            """.trimIndent(),
        )
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE music_folders ADD COLUMN lastScanStatus TEXT")
        db.execSQL("ALTER TABLE music_folders ADD COLUMN lastScanError TEXT")
        db.execSQL("ALTER TABLE music_folders ADD COLUMN lastScannedFileCount INTEGER")
        db.execSQL("ALTER TABLE music_folders ADD COLUMN lastScannedAudioCount INTEGER")
    }
}
