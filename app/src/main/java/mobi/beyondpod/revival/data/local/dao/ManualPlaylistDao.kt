package mobi.beyondpod.revival.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import mobi.beyondpod.revival.data.local.entity.ManualPlaylistEpisodeCrossRef

@Dao
interface ManualPlaylistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(ref: ManualPlaylistEpisodeCrossRef)

    @Query("DELETE FROM manual_playlist_episodes WHERE playlistId = :playlistId AND episodeId = :episodeId")
    suspend fun removeCrossRef(playlistId: Long, episodeId: Long)

    @Query("DELETE FROM manual_playlist_episodes WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)

    @Query("SELECT * FROM manual_playlist_episodes WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getPlaylistItems(playlistId: Long): List<ManualPlaylistEpisodeCrossRef>

    @Query("UPDATE manual_playlist_episodes SET position = :position WHERE playlistId = :playlistId AND episodeId = :episodeId")
    suspend fun updatePosition(playlistId: Long, episodeId: Long, position: Int)

    @Query("SELECT MAX(position) FROM manual_playlist_episodes WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: Long): Int?

    @Query("SELECT COUNT(*) FROM manual_playlist_episodes WHERE playlistId = :playlistId AND episodeId = :episodeId")
    suspend fun containsEpisode(playlistId: Long, episodeId: Long): Int

    /**
     * Remove all cross-refs for a set of episode IDs.
     * Called during feed deletion so My Episodes doesn't retain orphaned references
     * to episodes whose rows were CASCADE-deleted with their feed.
     */
    @Query("DELETE FROM manual_playlist_episodes WHERE episodeId IN (:episodeIds)")
    suspend fun removeEpisodes(episodeIds: List<Long>)
}
