package eu.kanade.tachiyomi.animeextension.all.missav

object GenreCache {

    private const val TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
    private const val SEPARATOR = "|||"

    private val cache = CacheManager<List<String>>(
        cacheName = "missav_genre_cache",
        ttlMillis = TTL_MILLIS,
        maxEntries = 2000,
        serializer = { it.joinToString(SEPARATOR) },
        deserializer = {
            if (it.isBlank()) emptyList() else it.split(SEPARATOR)
        },
    )

    fun get(url: String): List<String>? =
        cache.get(normalize(url))

    fun put(url: String, genres: List<String>) {
        if (genres.isNotEmpty()) {
            cache.put(normalize(url), genres.distinct())
        }
    }

    fun clear() {
        cache.clear()
    }

    private fun normalize(url: String): String =
        url.substringBefore("?").removeSuffix("/")
}
