package eu.kanade.tachiyomi.animeextension.zh.hanime1

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

enum class FilterUpdateState {
    NONE,
    UPDATING,
    COMPLETED,
    FAILED,
}

class Hanime1 : AnimeHttpSource(), ConfigurableAnimeSource {
    override val baseUrl = "https://hanime1.me"
    override val lang = "zh"
    override val name = "Hanime1.me"
    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(::checkFiltersInterceptor)
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    private val json: Json by injectLazy()
    private var filterUpdateState = FilterUpdateState.NONE
    private val uploadDateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
    }

    private val translationMap = mapOf(
        "影片屬性" to "Video Attributes",
        "人物關係" to "Relationships",
        "角色設定" to "Character Settings",
        "外貌身材" to "Appearance & Body",
        "故事劇情" to "Story",
        "性交體位" to "Sex Positions",
        "無碼" to "Uncensored",
        "無修正" to "Uncensored",
        "有修正" to "Censored",
        "AI解碼" to "AI Decoded",
        "中文字幕" to "Chinese Subtitles",
        "中文配音" to "Chinese Dub",
        "斷面圖" to "Cross-section View",
        "近親" to "Incest",
        "姐" to "Older Sister",
        "妹" to "Younger Sister",
        "母" to "Mother",
        "女兒" to "Daughter",
        "師生" to "Teacher-Student",
        "情侶" to "Lovers",
        "青梅竹馬" to "Childhood Friends",
        "同事" to "Coworkers",
        "JK" to "Schoolgirl",
        "處女" to "Virgin",
        "女學生" to "Schoolgirl",
        "御姐" to "Ojousama",
        "熟女" to "MILF",
        "母娘" to "MILF",
        "人妻" to "Wife",
        "女教師" to "Female Teacher",
        "男教師" to "Male Teacher",
        "女醫生" to "Female Doctor",
        "女病人" to "Female Patient",
        "護士" to "Nurse",
        "OL" to "Office Lady",
        "女警" to "Policewoman",
        "大小姐" to "Young Lady",
        "偶像" to "Idol",
        "女僕" to "Maid",
        "巫女" to "Shrine Maiden",
        "魔女" to "Witch",
        "修女" to "Nun",
        "風俗娘" to "Sex Worker",
        "公主" to "Princess",
        "女忍者" to "Kunoichi",
        "女戰士" to "Female Warrior",
        "女騎士" to "Female Knight",
        "魔法少女" to "Magical Girl",
        "異種族" to "Different Species",
        "天使" to "Angel",
        "妖精" to "Fairy",
        "魔物娘" to "Monster Girl",
        "魅魔" to "Succubus",
        "吸血鬼" to "Vampire",
        "女鬼" to "Female Ghost",
        "獸娘" to "Kemonomimi",
        "乳牛" to "Cow Girl",
        "機械娘" to "Robot Girl",
        "碧池" to "Slut",
        "痴女" to "Nympho",
        "雌小鬼" to "Bratty Girl",
        "不良少女" to "Delinquent Girl",
        "傲嬌" to "Tsundere",
        "病嬌" to "Yandere",
        "無口" to "Quiet",
        "無表情" to "Expressionless",
        "眼神死" to "Dead Eyes",
        "正太" to "Shota",
        "偽娘" to "Crossdresser",
        "短髮" to "Short Hair",
        "馬尾" to "Ponytail",
        "雙馬尾" to "Twin Tails",
        "巨乳" to "Big Breasts",
        "乳環" to "Nipple Piercing",
        "舌環" to "Tongue Piercing",
        "貧乳" to "Small Breasts",
        "黑皮膚" to "Dark Skin",
        "曬痕" to "Tan Lines",
        "眼鏡娘" to "Glasses",
        "獸耳" to "Animal Ears",
        "尖耳朵" to "Pointy Ears",
        "異色瞳" to "Heterochromia",
        "美人痣" to "Beauty Mark",
        "肌肉女" to "Muscular Girl",
        "白虎" to "Shaved Pussy",
        "陰毛" to "Pubic Hair",
        "腋毛" to "Armpit Hair",
        "大屌" to "Big Dick",
        "水手服" to "Sailor Uniform",
        "體操服" to "Gym Uniform",
        "泳裝" to "Swimsuit",
        "比基尼" to "Bikini",
        "死庫水" to "School Swimsuit",
        "和服" to "Kimono",
        "兔女郎" to "Bunny Girl",
        "圍裙" to "Apron",
        "啦啦隊" to "Cheerleader",
        "絲襪" to "Stockings",
        "吊襪帶" to "Garter Belt",
        "熱褲" to "Hot Pants",
        "迷你裙" to "Miniskirt",
        "性感內衣" to "Lingerie",
        "緊身衣" to "Bodysuit",
        "丁字褲" to "Thong",
        "高跟鞋" to "High Heels",
        "婚紗" to "Wedding Dress",
        "旗袍" to "Qipao",
        "古裝" to "Traditional Dress",
        "哥德蘿莉塔" to "Gothic Lolita",
        "口罩" to "Face Mask",
        "刺青" to "Tattoo",
        "淫紋" to "Lewd Tattoo",
        "身體寫字" to "Body Writing",
        "純愛" to "Pure Love",
        "戀愛喜劇" to "Romantic Comedy",
        "後宮" to "Harem",
        "開大車" to "Older Woman",
        "校園" to "School",
        "教室" to "Classroom",
        "公眾場合" to "Public",
        "公共廁所" to "Public Toilet",
        "NTR" to "Netorare",
        "精神控制" to "Mind Control",
        "藥物" to "Drugs",
        "痴漢" to "Molester",
        "阿嘿顏" to "Ahegao",
        "精神崩潰" to "Mind Break",
        "獵奇" to "Grotesque",
        "BDSM" to "BDSM",
        "綑綁" to "Bondage",
        "眼罩" to "Blindfold",
        "項圈" to "Collar",
        "調教" to "Training",
        "異物插入" to "Object Insertion",
        "尋歡洞" to "Glory Hole",
        "肉便器" to "Human Toilet",
        "性奴隸" to "Sex Slave",
        "胃凸" to "Stomach Bulge",
        "強制" to "Forced",
        "輪姦" to "Gangbang",
        "凌辱" to "Humiliation",
        "扯頭髮" to "Hair Pulling",
        "打屁股" to "Spanking",
        "肉棒打臉" to "Cock Slap",
        "性暴力" to "Sexual Violence",
        "逆強制" to "Reverse Rape",
        "女王樣" to "Queen",
        "母女丼" to "Mother-Daughter",
        "姐妹丼" to "Sister-Sister",
        "出軌" to "Cheating",
        "醉酒" to "Drunk",
        "攝影" to "Filming",
        "睡眠姦" to "Sleep Sex",
        "機械姦" to "Machine Sex",
        "蟲姦" to "Insect Sex",
        "性轉換" to "Gender Bender",
        "百合" to "Yuri",
        "耽美" to "BL",
        "時間停止" to "Time Stop",
        "異世界" to "Isekai",
        "怪獸" to "Monster",
        "哥布林" to "Goblin",
        "世界末日" to "Apocalypse",
        "手交" to "Handjob",
        "指交" to "Fingering",
        "乳交" to "Paizuri",
        "乳頭交" to "Nipple Fuck",
        "肛交" to "Anal",
        "雙洞齊下" to "Double Penetration",
        "腳交" to "Footjob",
        "素股" to "Frottage",
        "拳交" to "Fisting",
        "3P" to "Threesome",
        "群交" to "Orgy",
        "口交" to "Blowjob",
        "深喉嚨" to "Deep Throat",
        "口爆" to "Cum in Mouth",
        "吞精" to "Swallowing",
        "舔蛋蛋" to "Ball Licking",
        "舔穴" to "Cunnilingus",
        "69" to "69",
        "自慰" to "Masturbation",
        "腋交" to "Armpit Sex",
        "舔腋下" to "Armpit Licking",
        "髮交" to "Hairjob",
        "舔耳朵" to "Ear Licking",
        "內射" to "Creampie",
        "外射" to "External Cum",
        "顏射" to "Facial",
        "潮吹" to "Squirting",
        "懷孕" to "Pregnant",
        "噴奶" to "Lactation",
        "放尿" to "Watersports",
        "排便" to "Scat",
        "騎乘位" to "Cowgirl",
        "背後位" to "Doggy Style",
        "顏面騎乘" to "Face Sitting",
        "火車便當" to "Standing Carry",
        "車震" to "Car Sex",
        "性玩具" to "Sex Toys",
        "飛機杯" to "Onahole",
        "跳蛋" to "Vibrator",
        "毒龍鑽" to "Rimming",
        "觸手" to "Tentacles",
        "獸交" to "Bestiality",
        "頸手枷" to "Pillory",
        "著衣" to "Clothed Sex",
        "陰道外翻" to "Prolapse",
        "接吻" to "Kissing",
        "舌吻" to "French Kiss",
        "POV" to "POV",
        "扶他" to "Futanari",
        "全部" to "All",
        "裏番" to "Hentai",
        "泡麵番" to "Short Anime",
        "Motion Anime" to "Motion Anime",
        "3D動畫" to "3D Animation",
        "同人作品" to "Doujin Works",
        "MMD" to "MMD",
        "Cosplay" to "Cosplay",
        "最新上市" to "Newest Released",
        "最新上傳" to "Newest Uploaded",
        "本日排行" to "Today’s Ranking",
        "本週排行" to "Weekly Ranking",
        "本月排行" to "Monthly Ranking",
        "觀看次數" to "Most Viewed",
        "讚好比例" to "Best Liked Ratio",
        "時長最長" to "Longest Duration",
        "他們在看" to "People Watching",
        "強姦" to "Rape",
        "亂倫" to "Incest",
        "扶她" to "Futanari",
        "痴女" to "Lewd Woman",
        "蘿莉" to "Loli",
        "美少女" to "Beautiful Girl",
        "異種" to "Bestiality",
        "男同性戀" to "Yaoi",
    )

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        return SAnime.create().apply {
            genre = doc.select(".single-video-tag")
                .not("[data-toggle]")
                .eachText()
                .joinToString()

            author = doc.select("#video-artist-name").text()

            doc.select("script[type=application/ld+json]")
                .firstOrNull()
                ?.data()
                ?.let { data ->
                    val info = json.parseToJsonElement(data).jsonObject
                    title = info["name"]?.jsonPrimitive?.content ?: ""
                    description = info["description"]?.jsonPrimitive?.content ?: ""
                    thumbnail_url = info["thumbnailUrl"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonPrimitive
                        ?.content
                }

            val type = doc.select("a#video-artist-name + a").text().trim()
            if (type == "Bangumi" || type == "Short Episodes") {
                runBlocking {
                    try {
                        val filter = GenreFilter(arrayOf("", type))
                        filter.state = 1
                        val animesPage = getSearchAnime(
                            1,
                            title,
                            AnimeFilterList(filter),
                        )
                        animesPage.animes.firstOrNull()?.thumbnail_url?.let {
                            thumbnail_url = it
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Failed to get bangumi cover image", e)
                    }
                }
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val episodes = doc.select("#playlist-scroll > div")

        return episodes.mapIndexed { index, element ->
            SEpisode.create().apply {
                val href = element.select("a.overlay").attr("href")
                setUrlWithoutDomain(href)
                episode_number = (episodes.size - index).toFloat()
                name = element.select("div.card-mobile-title").text()

                if (href == response.request.url.toString()) {
                    doc.select("script[type=application/ld+json]")
                        .firstOrNull()
                        ?.data()
                        ?.let { data ->
                            json.parseToJsonElement(data)
                                .jsonObject["uploadDate"]
                                ?.jsonPrimitive
                                ?.content
                                ?.let { dateString ->
                                    runCatching {
                                        uploadDateFormat.parse(dateString)?.time
                                    }.getOrNull()
                                }
                        }?.let { date_upload = it }
                }
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val sources = doc.select("video source")
        val preferQuality = preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)

        return if (sources.isNotEmpty()) {
            sources.mapNotNull {
                val quality = it.attr("size")
                val url = it.attr("src").takeIf { src -> !src.startsWith("blob:") }
                url?.let { safeUrl -> Video(safeUrl, "${quality}P", safeUrl) }
            }.sortedByDescending { it.quality == preferQuality }
        } else {
            doc.select("script[type=application/ld+json]")
                .firstOrNull()
                ?.data()
                ?.let { data ->
                    json.parseToJsonElement(data)
                        .jsonObject["contentUrl"]
                        ?.jsonPrimitive
                        ?.content
                        ?.let { url -> listOf(Video(url, "Raw", url)) }
                } ?: emptyList()
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun latestUpdatesRequest(page: Int): Request =
        searchAnimeRequest(page, "", AnimeFilterList())

    override fun popularAnimeParse(response: Response): AnimesPage = searchAnimeParse(response)

    override fun popularAnimeRequest(page: Int): Request =
        searchAnimeRequest(page, "", AnimeFilterList(HotFilter))

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val animes = doc.select("div.search-doujin-videos.hidden-xs:not(:has(a[target=_blank]))")
            .takeIf { it.isNotEmpty() }
            ?: doc.select("a:not([target]) > .search-videos")

        val list = animes.map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.select("a[href]").attr("href"))
                thumbnail_url = it.select("img").attr("src")
                title = it.select(".card-mobile-title, .home-rows-videos-title")
                    .text()
                    .appendInvisibleChar()
                author = it.select(".card-mobile-user").text().takeIf { t -> t.isNotEmpty() }
            }
        }

        val hasNextPage = doc.select("li.page-item a.page-link[rel=next]").isNotEmpty()
        return AnimesPage(list, hasNextPage)
    }

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request {
        val httpUrl = "$baseUrl/search".toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) {
                addQueryParameter("query", query)
            }

            filters.forEach { filter ->
                when (filter) {
                    is QueryFilter -> {
                        if (filter.selected.isNotEmpty()) {
                            addQueryParameter(filter.key, filter.selected)
                        }
                    }
                    is BroadMatchFilter -> {
                        if (filter.state) {
                            addQueryParameter("broad_match", "on")
                        }
                    }
                    is TagFilter -> {
                        if (filter.state) {
                            addQueryParameter("tags[]", filter.name)
                        }
                    }
                    is TagsFilter -> {
                        filter.state.forEach { inner ->
                            if (inner is CategoryFilter) {
                                inner.state.forEach { tag ->
                                    if (tag is TagFilter && tag.state) {
                                        addQueryParameter("tags[]", tag.name)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
        }.build()

        return GET(httpUrl)
    }

    private fun checkFiltersInterceptor(chain: Interceptor.Chain): Response {
        if (filterUpdateState == FilterUpdateState.NONE) updateFilters()
        return chain.proceed(chain.request())
    }

    private fun updateFilters() {
        filterUpdateState = FilterUpdateState.UPDATING

        val exceptionHandler = CoroutineExceptionHandler { _, e ->
            Log.e(name, "Filter update failed", e)
            filterUpdateState = FilterUpdateState.FAILED
        }

        GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
            try {
                val searchDoc = client.newCall(GET("$baseUrl/search"))
                    .awaitSuccess()
                    .asJsoup()

                val genreList = searchDoc.select(
                    "div.genre-option div.hentai-sort-options",
                ).eachText()
                val sortList = searchDoc.select(
                    "div.hentai-sort-options-wrapper div.hentai-sort-options",
                ).eachText()
                val yearList = searchDoc.select(
                    "select#year option",
                ).eachAttr("value").map { it.ifEmpty { "All Years" } }
                val monthList = searchDoc.select(
                    "select#month option",
                ).eachAttr("value").map { it.ifEmpty { "All Months" } }

                val categoryDict = mutableMapOf<String, MutableList<String>>()
                var currentCategory = ""

                searchDoc.select("div#tags div.modal-body").first()?.children()?.forEach { element ->
                    when (element.tagName()) {
                        "h5" -> currentCategory = translationMap[element.text()] ?: element.text()
                        "label" -> {
                            val tagName = element.select("input[name]").attr("value")
                            val translated = translationMap[tagName] ?: tagName
                            categoryDict.getOrPut(currentCategory) { mutableListOf() }.add(translated)
                        }
                    }
                }

                preferences.edit().apply {
                    putString(PREF_KEY_GENRE_LIST, genreList.joinToString())
                    putString(PREF_KEY_SORT_LIST, sortList.joinToString())
                    putString(PREF_KEY_YEAR_LIST, yearList.joinToString())
                    putString(PREF_KEY_MONTH_LIST, monthList.joinToString())
                    putString(PREF_KEY_CATEGORY_LIST, json.encodeToString(categoryDict))
                    apply()
                }

                filterUpdateState = FilterUpdateState.COMPLETED
            } catch (e: Exception) {
                Log.e(name, "updateFilters exception", e)
                filterUpdateState = FilterUpdateState.FAILED
            }
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        createFilter(PREF_KEY_GENRE_LIST) { GenreFilter(it) },
        createFilter(PREF_KEY_SORT_LIST) { SortFilter(it) },
        DateFilter(
            createFilter(PREF_KEY_YEAR_LIST) { YearFilter(it) },
            createFilter(PREF_KEY_MONTH_LIST) { MonthFilter(it) },
        ),
        TagsFilter(createCategoryFilters()),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_KEY_VIDEO_QUALITY
            title = "Preferred Video Quality"
            entries = arrayOf("1080P", "720P", "480P")
            entryValues = arrayOf("1080P", "720P", "480P")
            setDefaultValue(DEFAULT_QUALITY)
            summary = "Current: ${preferences.getString(PREF_KEY_VIDEO_QUALITY, DEFAULT_QUALITY)}"
            setOnPreferenceChangeListener { _, newValue ->
                summary = "Current: ${newValue as String}"
                true
            }
        }.also { screen.addPreference(it) }
    }

    private fun String.appendInvisibleChar() = "$this\u200B"

    private fun <T : QueryFilter> createFilter(
        prefKey: String,
        block: (Array<String>) -> T,
    ): T {
        val saved = preferences.getString(prefKey, "")
        return if (saved.isNullOrEmpty()) {
            block(emptyArray())
        } else {
            block(
                saved.split(", ").toTypedArray(),
            )
        }
    }

    private fun createCategoryFilters(): List<AnimeFilter<*>> {
        val saved = preferences.getString(PREF_KEY_CATEGORY_LIST, null)
        val filters = mutableListOf<AnimeFilter<*>>(
            BroadMatchFilter(),
        )

        saved?.let {
            try {
                json.decodeFromString<Map<String, List<String>>>(it).forEach { (category, tags) ->
                    filters.add(CategoryFilter(category, tags.map { tag -> TagFilter("tags[]", tag) }))
                }
            } catch (e: Exception) {
                Log.e(name, "Error parsing category filters", e)
            }
        }

        return filters
    }

    private open class QueryFilter(
        val key: String,
        vals: Array<String>,
    ) : AnimeFilter.Select<String>(key, vals, 0) {
        val selected: String get() = if (state == 0 || values.isEmpty()) "" else values[state]
    }

    private class GenreFilter(vals: Array<String>) : QueryFilter("genre", vals)
    private class SortFilter(vals: Array<String>) : QueryFilter("sort", vals)
    private class YearFilter(vals: Array<String>) : QueryFilter("year", vals)
    private class MonthFilter(vals: Array<String>) : QueryFilter("month", vals)

    private class TagsFilter(
        state: List<AnimeFilter<*>>,
    ) : AnimeFilter.Group<AnimeFilter<*>>("Tags", state)

    private class CategoryFilter(
        name: String,
        state: List<TagFilter>,
    ) : AnimeFilter.Group<TagFilter>(name, state)

    private class TagFilter(
        val key: String,
        name: String,
    ) : AnimeFilter.CheckBox(name, false)

    private class DateFilter(
        private val year: YearFilter,
        private val month: MonthFilter,
    ) : AnimeFilter.Group<AnimeFilter<*>>("Date", listOf(year, month))

    private object HotFilter : AnimeFilter.Select<String>("Sort", arrayOf("Hot"), 0)
    private class BroadMatchFilter : AnimeFilter.CheckBox("Broad match (OR)", false)

    private suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val req = searchAnimeRequest(page, query, filters)
        val resp = client.newCall(req).awaitSuccess()
        return searchAnimeParse(resp)
    }

    companion object {
        private const val PREF_KEY_VIDEO_QUALITY = "pref_video_quality"
        private const val PREF_KEY_LANG = "pref_language"
        private const val PREF_KEY_GENRE_LIST = "pref_genre_list"
        private const val PREF_KEY_SORT_LIST = "pref_sort_list"
        private const val PREF_KEY_YEAR_LIST = "pref_year_list"
        private const val PREF_KEY_MONTH_LIST = "pref_month_list"
        private const val PREF_KEY_CATEGORY_LIST = "pref_category_list"
        private const val DEFAULT_QUALITY = "1080P"
    }
}
