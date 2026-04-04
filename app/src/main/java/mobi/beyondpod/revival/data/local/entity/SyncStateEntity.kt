package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,             // Singleton row
    val provider: SyncProvider = SyncProvider.NONE,
    val username: String? = null,
    val serverUrl: String = "https://gpodder.net",
    val deviceId: String = "",
    val lastSyncTimestamp: Long = 0L,
    val syncEnabled: Boolean = false,
    val syncEpisodeState: Boolean = true,    // Sync play position/state
    val syncSubscriptions: Boolean = true
)

enum class SyncProvider { NONE, GPODDER, NEXTCLOUD_GPODDERSYNC }
