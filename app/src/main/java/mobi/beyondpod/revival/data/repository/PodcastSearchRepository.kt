package mobi.beyondpod.revival.data.repository

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject

data class PodcastSearchResult(
    val trackName: String,
    val artistName: String,
    val artworkUrl: String,
    val feedUrl: String,
    val description: String
)

class PodcastSearchRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    suspend fun search(query: String): Result<List<PodcastSearchResult>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = URLEncoder.encode(query.trim(), "UTF-8")
                val url = "https://itunes.apple.com/search" +
                        "?term=$encoded&media=podcast&entity=podcast&limit=20"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "BeyondPodRevival/5.0")
                    .build()
                okHttpClient.newCall(request).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    val body = resp.body?.string() ?: error("Empty response")
                    val response = gson.fromJson(body, ItunesResponse::class.java)
                    response.results
                        .filter { !it.feedUrl.isNullOrBlank() }
                        .map { it.toDomain() }
                }
            }
        }

    // ── Internal Gson DTOs ────────────────────────────────────────────────────

    private data class ItunesResponse(
        @SerializedName("results") val results: List<ItunesResult> = emptyList()
    )

    private data class ItunesResult(
        @SerializedName("trackName")     val trackName: String?     = null,
        @SerializedName("artistName")    val artistName: String?    = null,
        @SerializedName("artworkUrl100") val artworkUrl100: String? = null,
        @SerializedName("feedUrl")       val feedUrl: String?       = null,
        @SerializedName("description")   val description: String?   = null
    )

    private fun ItunesResult.toDomain() = PodcastSearchResult(
        trackName   = trackName.orEmpty(),
        artistName  = artistName.orEmpty(),
        artworkUrl  = artworkUrl100.orEmpty(),
        feedUrl     = feedUrl.orEmpty(),
        description = description.orEmpty()
    )
}
