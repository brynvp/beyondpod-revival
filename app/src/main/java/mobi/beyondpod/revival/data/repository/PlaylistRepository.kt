package mobi.beyondpod.revival.data.repository

import kotlinx.coroutines.flow.Flow
import mobi.beyondpod.revival.data.local.entity.EpisodeEntity
import mobi.beyondpod.revival.data.local.entity.SmartPlaylistEntity

interface PlaylistRepository {
    fun getAllPlaylists(): Flow<List<SmartPlaylistEntity>>
    suspend fun getPlaylistById(id: Long): SmartPlaylistEntity?

    /** Returns the My Episodes playlist (isDefault = true). May be null before first-run seeding. */
    suspend fun getMyEpisodesPlaylist(): SmartPlaylistEntity?

    suspend fun createPlaylist(playlist: SmartPlaylistEntity): Long
    suspend fun updatePlaylist(playlist: SmartPlaylistEntity)

    /**
     * Delete a playlist. Throws [IllegalStateException] if [playlist.isDefault] is true —
     * the My Episodes playlist is indestructible (§7.5 rule #3).
     */
    suspend fun deletePlaylist(playlist: SmartPlaylistEntity)

    /**
     * Evaluate [playlist] rules against the current database state and return a live Flow
     * of matching episodes. Supports both SEQUENTIAL_BLOCKS and FILTER_RULES modes.
     * The returned Flow re-emits whenever the underlying episode data changes.
     */
    fun evaluateSmartPlaylist(playlist: SmartPlaylistEntity): Flow<List<EpisodeEntity>>

    /**
     * Seed the five default playlists on first run. No-op if already seeded.
     * Call from the Application on first launch.
     */
    suspend fun seedDefaultPlaylistsIfNeeded()
}
