package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_queue")
data class DownloadQueueEntity(
    @PrimaryKey val episodeId: Long,
    val queuePosition: Int,
    val addedAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)
