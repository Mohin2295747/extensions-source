package eu.kanade.tachiyomi.animeextension.all.missav

object GenreCache {
    private const val TTL = 7L * 24 * 60 * 60 * 1000
    private const val SEP = "|||"

    private val cache = CacheManager<List<String>>(
        cacheName = "missav_genre_cache",
        ttlMillis = TTL,
        maxEntries = 2000,
        serializer = { it.joinToString(SEP) },
        deserializer = { if (it.isBlank()) emptyList() else it.split(SEP) }
    )

    fun get(url: String): List<String>? =
        cache.get(normalize(url))

    fun put(url: String, genres: List<String>) {
        if (genres.isNotEmpty()) {
            cache.put(normalize(url), genres.distinct())
        }
    }

    fun clear() = cache.clear()

    private fun normalize(url: String) =
        url.substringBefore("?").removeSuffix("/")
}
