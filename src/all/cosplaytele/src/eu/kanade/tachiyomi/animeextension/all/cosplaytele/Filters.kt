package eu.kanade.tachiyomi.animeextension.all.cosplaytele

import eu.kanade.tachiyomi.animesource.model.AnimeFilter

val CATEGORIES = arrayOf(
    "All" to "",
    "Cosplay Nude" to "category/nude",
    "Cosplay Ero" to "category/no-nude",
    "Cosplay" to "category/cosplay"
)

class CategoryFilter(
    displayName: String,
    private val vals: Array<String>
) : AnimeFilter.Select<String>(displayName, vals)
