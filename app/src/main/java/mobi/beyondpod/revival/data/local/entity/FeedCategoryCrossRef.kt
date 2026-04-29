package mobi.beyondpod.revival.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Supports BeyondPod's original feature: each feed can belong to up to 2 categories.
 * Primary category (isPrimary = true) drives navigator placement.
 * Secondary category makes the feed visible in a second category's episode list.
 *
 * FK policy:
 * - CASCADE on feedId  → cross-ref row removed when the feed is deleted (correct).
 * - CASCADE on categoryId → cross-ref row removed when the category is deleted (correct).
 *   This does NOT delete the feed — CASCADE on a junction FK only removes the junction row.
 *   CategoryRepository still nulls out primaryCategoryId/secondaryCategoryId on affected
 *   FeedEntity rows so they appear in Uncategorized. See §7.3 Category Deletion.
 *
 * (DB version 4 — added categoryId FK. Migration 3→4 in BeyondPodDatabase.)
 */
@Entity(
    tableName = "feed_category_cross_ref",
    primaryKeys = ["feedId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = FeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE   // Only removes this junction row — feed is unaffected
        )
    ],
    indices = [Index("feedId"), Index("categoryId")]
)
data class FeedCategoryCrossRef(
    val feedId: Long,
    val categoryId: Long,
    val isPrimary: Boolean = true
)
