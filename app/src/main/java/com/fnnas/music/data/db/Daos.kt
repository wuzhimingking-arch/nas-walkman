package com.fnnas.music.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NasDao {
    @Query("SELECT * FROM nas_servers ORDER BY updatedAt DESC LIMIT 1")
    fun observeActiveNas(): Flow<NasServerEntity?>

    @Query("SELECT * FROM nas_servers ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActiveNas(): NasServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(server: NasServerEntity): Long

    @Query("UPDATE nas_servers SET lastConnectedAt = :connectedAt, updatedAt = :connectedAt WHERE id = :id")
    suspend fun markConnected(id: Long, connectedAt: Long)

    @Query("DELETE FROM nas_servers")
    suspend fun clear()
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY lower(title), lower(fileName)")
    fun observeTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN play_history h ON h.trackId = t.id
        GROUP BY t.id
        ORDER BY max(h.playedAt) DESC
        LIMIT 50
        """
    )
    fun observeRecent(): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE lower(title) LIKE '%' || lower(:query) || '%'
           OR lower(ifnull(artist, '')) LIKE '%' || lower(:query) || '%'
           OR lower(ifnull(album, '')) LIKE '%' || lower(:query) || '%'
           OR lower(fileName) LIKE '%' || lower(:query) || '%'
           OR lower(remotePath) LIKE '%' || lower(:query) || '%'
        ORDER BY lower(title), lower(fileName)
        """
    )
    fun search(query: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrack(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE nasServerId = :nasServerId AND remotePath = :remotePath LIMIT 1")
    suspend fun getTrackByRemotePath(nasServerId: Long, remotePath: String): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("UPDATE tracks SET isFavorite = CASE isFavorite WHEN 1 THEN 0 ELSE 1 END, updatedAt = :updatedAt WHERE id = :id")
    suspend fun toggleFavorite(id: String, updatedAt: Long)

    @Query("UPDATE tracks SET localCachePath = :path, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateCachePath(id: String, path: String?, updatedAt: Long)

    @Query("UPDATE tracks SET localCachePath = NULL, updatedAt = :updatedAt")
    suspend fun clearAllCachePaths(updatedAt: Long)

    @Query("DELETE FROM tracks WHERE nasServerId = :nasServerId AND lastSeenScanId != :scanId")
    suspend fun deleteNotSeenInScan(nasServerId: Long, scanId: Long)

    @Query("DELETE FROM tracks WHERE nasServerId = :nasServerId")
    suspend fun deleteAllForNas(nasServerId: Long)
}

@Dao
interface PlaylistDao {
    @Query(
        """
        SELECT p.id, p.name, count(pt.trackId) AS trackCount
        FROM playlists p
        LEFT JOIN playlist_tracks pt ON pt.playlistId = p.id
        GROUP BY p.id
        ORDER BY p.createdAt
        """
    )
    fun observePlaylists(): Flow<List<PlaylistSummary>>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON pt.trackId = t.id
        WHERE pt.playlistId = :playlistId
        ORDER BY pt.sortOrder, pt.createdAt
        """
    )
    fun observePlaylistTracks(playlistId: Long): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String, updatedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("SELECT ifnull(max(sortOrder), -1) + 1 FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun nextSortOrder(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrack(entity: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrack(playlistId: Long, trackId: String)
}

@Dao
interface PlayHistoryDao {
    @Insert
    suspend fun insert(entity: PlayHistoryEntity)
}

@Dao
interface CacheDao {
    @Query("SELECT ifnull(sum(size), 0) FROM cache_items")
    fun observeCacheBytes(): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CacheItemEntity)

    @Query("DELETE FROM cache_items WHERE trackId = :trackId")
    suspend fun deleteForTrack(trackId: String)

    @Query("DELETE FROM cache_items")
    suspend fun clear()
}
