package mobi.beyondpod.revival.ui.theme

import androidx.compose.ui.graphics.Color

// ── BeyondPod brand palette (§12) ────────────────────────────────────────────
val BeyondPodBlue      = Color(0xFF1565C0)
val BeyondPodBlueDark  = Color(0xFF003C8F)
val BeyondPodBlueLight = Color(0xFF5E92F3)
val BeyondPodOrange    = Color(0xFFFF6D00)   // Accent / action

// ── Dark surface palette ──────────────────────────────────────────────────────
val SurfaceDark        = Color(0xFF121212)
val SurfaceVariantDark = Color(0xFF1E1E1E)
val OnSurfaceDark      = Color(0xFFE0E0E0)

// ── Episode state indicators ──────────────────────────────────────────────────
val EpisodeNew         = BeyondPodBlue       // Blue left border for NEW episodes
val EpisodeInProgress  = BeyondPodOrange     // Orange left border for IN_PROGRESS
val EpisodePlayed      = Color(0xFF616161)   // Greyed-out for PLAYED
val EpisodeStarred     = Color(0xFFFFD600)   // Gold star