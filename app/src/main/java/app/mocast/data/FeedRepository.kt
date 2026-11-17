package app.mocast.data

import app.mocast.net.FeedResolver
import app.mocast.net.Http
import app.mocast.net.RssParser
import app.mocast.net.TranscriptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FeedRepository(private val store: Store) {

    suspend fun subscribe(input: String): Show = withContext(Dispatchers.IO) {
        val resolved = FeedResolver.resolve(input)
        val showId = RssParser.stableId("show", resolved.feedUrl)
        val xml = Http.get(resolved.feedUrl, "application/rss+xml, application/atom+xml, application/xml, text/xml")
        val parsed = RssParser.parse(xml, showId, resolved.source)
        val show = Show(
            id = showId,
            title = parsed.title,
            author = parsed.author,
            artwork = parsed.artwork,
            description = parsed.description,
            feedUrl = resolved.feedUrl,
            source = resolved.source,
            lastUpdated = System.currentTimeMillis(),
            episodeCount = parsed.episodes.size
        )
        store.upsertShow(show)
        store.saveEpisodes(showId, parsed.episodes)
        show
    }

    suspend fun refresh(show: Show): Show = withContext(Dispatchers.IO) {
        val xml = Http.get(show.feedUrl, "application/rss+xml, application/atom+xml, application/xml, text/xml")
        val parsed = RssParser.parse(xml, show.id, show.source)
        val updated = show.copy(
            title = parsed.title.ifBlank { show.title },
            author = parsed.author.ifBlank { show.author },
            artwork = parsed.artwork.ifBlank { show.artwork },
            description = parsed.description.ifBlank { show.description },
            lastUpdated = System.currentTimeMillis(),
            episodeCount = parsed.episodes.size
        )
        store.upsertShow(updated)
        store.saveEpisodes(show.id, parsed.episodes)
        updated
    }

    suspend fun transcriptOf(episode: Episode): List<Cue> = withContext(Dispatchers.IO) {
        if (episode.transcriptUrl.isBlank()) return@withContext emptyList()
        val raw = Http.get(episode.transcriptUrl)
        TranscriptParser.parse(raw)
    }

    fun episodesOf(showId: String): List<Episode> = store.loadEpisodes(showId)

    fun shows(): List<Show> = store.listShows()

    fun removeShow(showId: String) = store.removeShow(showId)

    fun progressOf(episodeId: String): Long = store.progressOf(episodeId)

    fun saveProgress(episodeId: String, positionMs: Long) = store.saveProgress(episodeId, positionMs)
}
