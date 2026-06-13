package tech.peakedge.naswalkman.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Database(
    entities = [
        NasServerEntity::class,
        MusicFolderEntity::class,
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        PlayHistoryEntity::class,
        CacheItemEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(ConnectionModeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nasDao(): NasDao
    abstract fun musicFolderDao(): MusicFolderDao
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun cacheDao(): CacheDao
}

class ConnectionModeConverter {
    @TypeConverter
    fun fromMode(mode: NasConnectionMode): String = mode.name

    @TypeConverter
    fun toMode(value: String): NasConnectionMode =
        runCatching { NasConnectionMode.valueOf(value) }.getOrDefault(NasConnectionMode.FN_CONNECT)

    @TypeConverter
    fun fromMusicSourceType(type: MusicSourceType): String = type.name

    @TypeConverter
    fun toMusicSourceType(value: String): MusicSourceType =
        runCatching { MusicSourceType.valueOf(value) }.getOrDefault(MusicSourceType.NAS)
}
