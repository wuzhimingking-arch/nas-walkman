package com.fnnas.music.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class NasConnectionMode {
    FN_CONNECT,
    REMOTE_URL,
    WEBDAV_ADVANCED,
}

@Entity(tableName = "nas_servers")
data class NasServerEntity(
    @PrimaryKey val id: Long = 1L,
    val name: String,
    val baseUrl: String,
    val mode: NasConnectionMode = NasConnectionMode.FN_CONNECT,
    val inputAddress: String = baseUrl,
    val resolvedBaseUrl: String = baseUrl,
    val username: String,
    val encryptedPassword: String,
    val musicRootPath: String,
    val allowMobilePlayback: Boolean,
    val autoScanWifiOnly: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastConnectedAt: Long? = null,
)

@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["nasServerId", "remotePath"], unique = true),
        Index(value = ["title"]),
        Index(value = ["isFavorite"]),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val nasServerId: Long,
    val remotePath: String,
    val fileName: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val fileSize: Long? = null,
    val modifiedAt: String? = null,
    val coverCachePath: String? = null,
    val isFavorite: Boolean = false,
    val localCachePath: String? = null,
    val lastSeenScanId: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("trackId")],
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val trackId: String,
    val sortOrder: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "play_history",
    indices = [Index("trackId"), Index("playedAt")],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackId: String,
    val playedAt: Long,
    val progressMs: Long,
)

@Entity(
    tableName = "cache_items",
    indices = [Index("trackId", unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CacheItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val trackId: String,
    val localPath: String,
    val size: Long,
    val cachedAt: Long,
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val trackCount: Int,
)
