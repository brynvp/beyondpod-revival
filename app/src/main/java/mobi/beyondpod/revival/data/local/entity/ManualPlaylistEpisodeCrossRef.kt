package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "manual_playlist_episodes",
    primaryKeys = ["playlistId", "episodeId"]
)
data class ManualPlaylistEpisodeCrossRef(
    val playlistId: Long,
    val episodeId: Long,
    val position: Int
)
