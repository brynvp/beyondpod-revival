package mobi.beyondpod.revival.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistEntity

@Dao
interface SmartPlaylistDao {
    @Query("SELECT * FROM smart_playlists ORDER BY sortOrder ASC, name ASC")
    fun getAllPlaylists(): Flow<List<SmartPlaylistEntity>>

    @Query("SELECT * FROM smart_playlists ORDER BY sortOrder ASC, name ASC")
    suspend fun getAllPlaylistsList(): List<SmartPlaylistEntity>

    @Query("SELECT * FROM smart_playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): SmartPlaylistEntity?

    @Upsert
    suspend fun upsertPlaylist(playlist: SmartPlaylistEntity): Long

    @Delete
    suspend fun deletePlaylist(playlist: SmartPlaylistEntity)

    @Query("SELECT * FROM smart_playlists WHERE isDefault = 1 LIMIT 1")
    suspend fun getMyEpisodesPlaylist(): SmartPlaylistEntity?
}
