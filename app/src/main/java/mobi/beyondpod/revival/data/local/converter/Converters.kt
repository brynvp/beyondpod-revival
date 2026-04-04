package mobi.beyondpod.revival.data.local.converter

import androidx.room.TypeConverter
import mobi.beyondpod.revival.data.local.entity.DownloadStateEnum
import mobi.beyondpod.revival.data.local.entity.DownloadStrategy
import mobi.beyondpod.revival.data.local.entity.EpisodeSortOrder
import mobi.beyondpod.revival.data.local.entity.OnEmptyAction
import mobi.beyondpod.revival.data.local.entity.PlayState
import mobi.beyondpod.revival.data.local.entity.PlaylistRuleMode
import mobi.beyondpod.revival.data.local.entity.SyncProvider

class Converters {

    // ── PlayState ────────────────────────────────────────────────────────────
    @TypeConverter
    fun fromPlayState(value: PlayState): String = value.name

    @TypeConverter
    fun toPlayState(value: String): PlayState = PlayState.valueOf(value)

    // ── DownloadStateEnum ────────────────────────────────────────────────────
    @TypeConverter
    fun fromDownloadStateEnum(value: DownloadStateEnum): String = value.name

    @TypeConverter
    fun toDownloadStateEnum(value: String): DownloadStateEnum = DownloadStateEnum.valueOf(value)

    // ── DownloadStrategy ─────────────────────────────────────────────────────
    @TypeConverter
    fun fromDownloadStrategy(value: DownloadStrategy): String = value.name

    @TypeConverter
    fun toDownloadStrategy(value: String): DownloadStrategy = DownloadStrategy.valueOf(value)

    // ── EpisodeSortOrder (nullable — FeedEntity.episodeSortOrder is nullable) ─
    @TypeConverter
    fun fromEpisodeSortOrder(value: EpisodeSortOrder?): String? = value?.name

    @TypeConverter
    fun toEpisodeSortOrder(value: String?): EpisodeSortOrder? =
        value?.let { EpisodeSortOrder.valueOf(it) }

    // ── PlaylistRuleMode ─────────────────────────────────────────────────────
    @TypeConverter
    fun fromPlaylistRuleMode(value: PlaylistRuleMode): String = value.name

    @TypeConverter
    fun toPlaylistRuleMode(value: String): PlaylistRuleMode = PlaylistRuleMode.valueOf(value)

    // ── OnEmptyAction ────────────────────────────────────────────────────────
    @TypeConverter
    fun fromOnEmptyAction(value: OnEmptyAction): String = value.name

    @TypeConverter
    fun toOnEmptyAction(value: String): OnEmptyAction = OnEmptyAction.valueOf(value)

    // ── SyncProvider ─────────────────────────────────────────────────────────
    @TypeConverter
    fun fromSyncProvider(value: SyncProvider): String = value.name

    @TypeConverter
    fun toSyncProvider(value: String): SyncProvider = SyncProvider.valueOf(value)
}
