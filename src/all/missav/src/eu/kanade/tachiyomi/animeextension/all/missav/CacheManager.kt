package eu.kanade.tachiyomi.animeextension.all.missav

import android.app.Application
import android.content.SharedPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections

class CacheManager<T>(
    cacheName: String,
    private val ttlMillis: Long,
    private val maxEntries: Int,
    private val serializer: (T) -> String,
    private val deserializer: (String) -> T
) {

    private val prefs: SharedPreferences =
        Injekt.get<Application>().getSharedPreferences(cacheName, 0)

    private val memory =
        Collections.synchronizedMap(mutableMapOf<String, Pair<Long, T>>())

    @Synchronized
    fun get(key: String): T? {
        val now = System.currentTimeMillis()

        memory[key]?.let { (time, value) ->
            if (now - time <= ttlMillis) return value
            memory.remove(key)
        }

        val raw = prefs.getString(key, null) ?: return null
        val split = raw.split("|", limit = 2)
        if (split.size != 2) return null

        val time = split[0].toLongOrNull() ?: return null
        if (now - time > ttlMillis) {
            remove(key)
            return null
        }

        val value = runCatching { deserializer(split[1]) }.getOrNull() ?: return null
        memory[key] = time to value
        return value
    }

    @Synchronized
    fun put(key: String, value: T) {
        val now = System.currentTimeMillis()
        memory[key] = now to value
        prefs.edit().putString(key, "$now|${serializer(value)}").apply()
        if (prefs.all.size > maxEntries) trim()
    }

    @Synchronized
    fun remove(key: String) {
        memory.remove(key)
        prefs.edit().remove(key).apply()
    }

    @Synchronized
    fun clear() {
        memory.clear()
        prefs.edit().clear().apply()
    }

    private fun trim() {
        val entries = prefs.all.entries.sortedBy {
            (it.value as String).substringBefore("|").toLongOrNull() ?: Long.MAX_VALUE
        }
        val removeCount = entries.size - maxEntries
        if (removeCount <= 0) return
        val editor = prefs.edit()
        entries.take(removeCount).forEach { editor.remove(it.key) }
        editor.apply()
    }
}
