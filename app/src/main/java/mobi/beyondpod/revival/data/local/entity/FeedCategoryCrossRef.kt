package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Supports BeyondPod's original feature: each feed can belong to up to 2 categories.
 * Primary category (isPrimary = true) drives navigator placement.
 * Secondary category makes the feed visible in a second category's episode list.
 *
 * FK policy: CASCADE on feedId (feed deleted → remove cross-ref rows).
 * NO cascade on categoryId — deleting a category must NOT delete feeds or cross-ref rows
 * automatically. The CategoryRepository must manually delete cross-ref rows before deleting
 * the CategoryEntity, then null out primaryCategoryId/secondaryCategoryId on affected FeedEntity
 * rows. This is the "move to Uncategorized" behaviour. See §7.3 Category Deletion.
 */
@Entity(
    tableName = "feed_category_cross_ref",
    primaryKeys = ["feedId", "categoryId"],
    foreignKeys = [ForeignKey(
        entity = FeedEntity::class,
        parentColumns = ["id"],
        childColumns = ["feedId"],
        onDelete = ForeignKey.CASCADE   // Cross-ref removed when feed is deleted — correct
    )],
    // Index on feedId for FK performance; categoryId is not a FK (deliberate — see KDoc above).
    indices = [Index("feedId")]
)
data class FeedCategoryCrossRef(
    val feedId: Long,
    val categoryId: Long,
    val isPrimary: Boolean = true
)
