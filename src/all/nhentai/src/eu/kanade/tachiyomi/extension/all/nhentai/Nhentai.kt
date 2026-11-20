package eu.kanade.tachiyomi.extension.all.nhentai

import android.content.SharedPreferences
import android.webkit.CookieManager
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

@Source
class NHentai(
    override val name: String,
    override val lang: String,
    override val baseUrl: String,
    override val id: Long,
) : KeiSource(),
    ConfigurableSource {

    private val nhLang: String
        get() = when (lang) {
            "en" -> "english"
            "ja" -> "japanese"
            "zh" -> "chinese"
            "vi" -> "vietnamese"
            else -> ""
        }

    val apiUrl get() = "$baseUrl/api/v2"

    private val baseUrlHost by lazy { baseUrl.toHttpUrl().host }

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val galleryCacheControl = CacheControl.Builder().maxStale(2, TimeUnit.HOURS).build()

    private val nhConfig = AtomicReference<NHConfig>()

    private var displayFullTitle: Boolean = when (preferences.getString(TITLE_PREF, "full")) {
        "full" -> true
        else -> false
    }

    val apiKey: String?
        get() = preferences.getString(API_KEY, "")

    val cookieToken: String
        get() = try {
            client.cookieJar.loadForRequest(baseUrl.toHttpUrl())
                .firstOrNull { it.name == "access_token" }
                ?.value.orEmpty()
        } catch (_: Exception) {
            try {
                CookieManager.getInstance().getCookie(baseUrl)
                    ?.split("; ")
                    ?.firstOrNull { it.startsWith("access_token=") }
                    ?.removePrefix("access_token=") ?: ""
            } catch (_: Exception) {
                ""
            }
        }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        val (permits, period) = preferences.parseRateLimit()
        addInterceptor(NhApiRetryInterceptor())
        addNetworkInterceptor(NhGalleryCacheInterceptor())
        addNetworkInterceptor(NhAuthorizationInterceptor())
        rateLimit(permits, period.seconds) { it.host == baseUrlHost }
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("Referer", "$baseUrl/")
    }

    private val apiHeaders: Headers
        get() = headers.newBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("X-Requested-With", "XMLHttpRequest")
            .build()

    private val htmlHeaders: Headers
        get() = headers.newBuilder()
            .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .set("Accept-Language", "en-US,en;q=0.9")
            .build()

    override suspend fun getPopularManga(page: Int): MangasPage = try {
        val res = getPopularMangaApi(page)
        if (res.mangas.isNotEmpty()) res else getPopularMangaHtml(page)
    } catch (_: Exception) {
        getPopularMangaHtml(page)
    }

    private suspend fun getPopularMangaApi(page: Int): MangasPage {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", if (nhLang.isEmpty()) "\"\"" else "language:$nhLang")
            .addQueryParameter("sort", "popular")
            .addQueryParameter("page", page.toString())
        return client.get(url.build(), apiHeaders).toMangasPage()
    }

    private suspend fun getPopularMangaHtml(page: Int): MangasPage {
        if (nhLang.isNotEmpty()) {
            try {
                val url = "$baseUrl/search/?q=language:$nhLang&sort=popular&page=$page"
                val response = client.get(url.toHttpUrl(), htmlHeaders)
                val result = parseHtmlMangasPage(response)
                if (result.mangas.isNotEmpty()) return result
            } catch (_: Exception) {}
        }
        val response = client.get("$baseUrl/?page=$page".toHttpUrl(), htmlHeaders)
        return parseHtmlMangasPage(response)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = try {
        val res = getLatestUpdatesApi(page)
        if (res.mangas.isNotEmpty()) res else getLatestUpdatesHtml(page)
    } catch (_: Exception) {
        getLatestUpdatesHtml(page)
    }

    private suspend fun getLatestUpdatesApi(page: Int): MangasPage {
        val url = if (nhLang.isEmpty()) {
            "$apiUrl/galleries".toHttpUrl().newBuilder()
        } else {
            "$apiUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", "language:$nhLang")
        }
        url.addQueryParameter("page", page.toString())
        return client.get(url.build(), apiHeaders).toMangasPage()
    }

    private suspend fun getLatestUpdatesHtml(page: Int): MangasPage {
        if (nhLang.isNotEmpty()) {
            try {
                val url = "$baseUrl/search/?q=language:$nhLang&page=$page"
                val response = client.get(url.toHttpUrl(), htmlHeaders)
                val result = parseHtmlMangasPage(response)
                if (result.mangas.isNotEmpty()) return result
            } catch (_: Exception) {}
        }
        val response = client.get("$baseUrl/?page=$page".toHttpUrl(), htmlHeaders)
        return parseHtmlMangasPage(response)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val galleryId = when {
            query.startsWith(PREFIX_ID_SEARCH) -> query.removePrefix(PREFIX_ID_SEARCH)
            query.toIntOrNull() != null -> query
            else -> null
        }
        if (galleryId != null) {
            return MangasPage(listOf(getGalleryManga(galleryId)), false)
        }

        val filterList = if (filters.isEmpty()) getFilterList(null) else filters
        val nhLangSearch = if (nhLang.isEmpty() || query.contains("language:")) "" else "language:$nhLang "
        val advQuery = filterList.combineQuery()
        val fullQuery = "$query $nhLangSearch$advQuery".trim()
        val sort = filterList.firstInstanceOrNull<SortFilter>()?.toUriPart()

        if (fullQuery.isEmpty()) {
            return if (!sort.isNullOrEmpty() && sort != "date") {
                getPopularManga(page)
            } else {
                getLatestUpdates(page)
            }
        }

        var apiError: Exception? = null
        val res = try {
            getSearchMangaListApi(page, fullQuery, filterList)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            apiError = e
            null
        }
        if (res != null && res.mangas.isNotEmpty()) return res

        val htmlRes = try {
            getSearchMangaListHtml(page, fullQuery, filterList)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Exception("API: ${apiError?.message ?: "0 results"} | HTML: ${e.message}")
        }
        if (htmlRes.mangas.isEmpty() && apiError != null) {
            throw Exception("API: ${apiError.message} | query=$fullQuery")
        }
        return htmlRes
    }

    private suspend fun getSearchMangaListApi(page: Int, fullQuery: String, filterList: FilterList): MangasPage {
        val favoriteFilter = filterList.firstInstanceOrNull<FavoriteFilter>()
        val offsetPage =
            filterList.firstInstanceOrNull<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        if (favoriteFilter?.state == true) {
            val favUrl = "$apiUrl/favorites".toHttpUrl().newBuilder()
                .apply {
                    if (fullQuery.isNotEmpty()) {
                        addQueryParameter("q", fullQuery)
                    }
                }
                .addQueryParameter("page", offsetPage.toString())
            return client.get(favUrl.build(), apiHeaders).toMangasPage()
        }

        val sort = filterList.firstInstanceOrNull<SortFilter>()?.toUriPart()
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", fullQuery)
            .apply {
                if (!sort.isNullOrEmpty() && sort != "date") {
                    addQueryParameter("sort", sort)
                }
            }
        url.addQueryParameter("page", offsetPage.toString())
        return client.get(url.build(), apiHeaders).toMangasPage()
    }

    private suspend fun getSearchMangaListHtml(page: Int, fullQuery: String, filterList: FilterList): MangasPage {
        val favoriteFilter = filterList.firstInstanceOrNull<FavoriteFilter>()
        val offsetPage =
            filterList.firstInstanceOrNull<OffsetPageFilter>()?.state?.toIntOrNull()?.plus(page) ?: page

        if (favoriteFilter?.state == true) {
            val favUrl = "$baseUrl/favorites/".toHttpUrl().newBuilder()
                .apply {
                    if (fullQuery.isNotEmpty()) {
                        addQueryParameter("q", fullQuery)
                    }
                }
                .addQueryParameter("page", offsetPage.toString())
            val response = client.get(favUrl.build(), htmlHeaders)
            return parseHtmlMangasPage(response)
        }

        val sort = filterList.firstInstanceOrNull<SortFilter>()?.toUriPart()
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("q", fullQuery)
            .apply {
                if (!sort.isNullOrEmpty() && sort != "date") {
                    addQueryParameter("sort", sort)
                }
            }
        url.addQueryParameter("page", offsetPage.toString())
        val response = client.get(url.build(), htmlHeaders)
        return parseHtmlMangasPage(response)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrlHost || url.pathSegments.firstOrNull() != "g") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return getGalleryManga(id)
    }

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val id = manga.url.removeSurrounding("/g/", "/")
        return try {
            val thumbServers = getConfig().thumbServers
            client.get("$apiUrl/galleries/$id/related", apiHeaders).parseAs<PaginatedResponse<GalleryItem>>()
                .result.mapNotNull { it.toSMangaOrNull(thumbServers.random(), displayFullTitle) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.removeSurrounding("/g/", "/")
        return try {
            val data = client.get("$apiUrl/galleries/$id", apiHeaders, cacheControl = galleryCacheControl).parseAs<Hentai>()
            SMangaUpdate(
                manga = data.toSManga(getConfig().thumbServers.random(), displayFullTitle),
                chapters = listOf(data.toSChapter()),
            )
        } catch (_: Exception) {
            val response = client.get("$baseUrl/g/$id/".toHttpUrl(), htmlHeaders)
            val doc = response.asJsoup()
            SMangaUpdate(
                manga = parseHtmlMangaDetails(doc, id),
                chapters = listOf(
                    SChapter.create().apply {
                        name = "Chapter"
                        scanlator = doc.select(".tag-container:contains(Groups) .name").joinToString { it.text() }.ifEmpty { null }
                        url = "/g/$id/"
                    },
                ),
            )
        }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl${manga.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = chapter.url.removeSurrounding("/g/", "/")
        return try {
            val pages = getPageListApi(id)
            if (pages.isNotEmpty()) pages else getPageListHtml(id)
        } catch (_: Exception) {
            getPageListHtml(id)
        }
    }

    private suspend fun getPageListApi(id: String): List<Page> {
        val data = getGallery(id)
        val imageServers = getConfig().imageServers
        return data.pages.mapIndexed { i, page ->
            val host = imageServers.random().removeSuffix("/")
            val path = page.path.removePrefix("/")
            Page(i, imageUrl = "$host/$path")
        }
    }

    private suspend fun getPageListHtml(id: String): List<Page> {
        val response = client.get("$baseUrl/g/$id/".toHttpUrl(), htmlHeaders)
        return parseHtmlPages(response.asJsoup())
    }

    override fun getFilterList(data: JsonElement?): FilterList = getFilters(preferences.getString(SORT_PREF, "date"))

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = TITLE_PREF
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            summary = "%s"
            setDefaultValue("full")

            setOnPreferenceChangeListener { _, newValue ->
                displayFullTitle = when (newValue) {
                    "full" -> true
                    else -> false
                }
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = SORT_PREF
            title = SORT_PREF
            entries = SORT_OPTIONS.map { it.first }.toTypedArray()
            entryValues = SORT_OPTIONS.map { it.second }.toTypedArray()
            summary = "%s"
            setDefaultValue("date")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = API_KEY
            title = "API key"
            summary = "Profile > Settings > API Keys"
            setDefaultValue("")
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = RATE_LIMIT_PREF
            title = "Network Rate Limit"
            summary = "%s"
            entries = RATE_LIMIT_OPTIONS.map { it.first }.toTypedArray()
            entryValues = RATE_LIMIT_OPTIONS.map { it.second }.toTypedArray()
            setDefaultValue(RATE_LIMIT_DEFAULT)
            setOnPreferenceChangeListener { _, _ ->
                Toast.makeText(screen.context, "Restart app to apply", Toast.LENGTH_LONG).show()
                true
            }
        }.also(screen::addPreference)
    }

    private suspend fun getConfig(): NHConfig {
        nhConfig.get()?.let { return it }
        return try {
            client.get("$apiUrl/config", apiHeaders).parseAs<NHConfig>().also {
                require(it.imageServers.isNotEmpty() && it.thumbServers.isNotEmpty())
                nhConfig.set(it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            nhConfig.compareAndSet(
                null,
                NHConfig(
                    (1..4).map { n -> "https://i$n.nhentai.net" },
                    (1..4).map { n -> "https://t$n.nhentai.net" },
                ),
            )
            nhConfig.get()
        }
    }

    private suspend fun getGalleryManga(id: String): SManga = try {
        getGallery(id).toSManga(getConfig().thumbServers.random(), displayFullTitle).apply { initialized = true }
    } catch (_: Exception) {
        val response = client.get("$baseUrl/g/$id/".toHttpUrl(), htmlHeaders)
        parseHtmlMangaDetails(response.asJsoup(), id)
    }

    private suspend fun getGallery(id: String): Hentai = client.get("$apiUrl/galleries/$id", apiHeaders).parseAs()

    private suspend fun Response.toMangasPage(): MangasPage {
        val currentPage = request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val res = parseAs<PaginatedResponse<GalleryItem>>()
        val thumbServers = getConfig().thumbServers
        val mangas = res.result.mapNotNull { it.toSMangaOrNull(thumbServers.random(), displayFullTitle) }
        val hasNextPage =
            (res.numPages != null && res.numPages > currentPage) ||
                (res.numPages == null && res.total != null && res.total > currentPage * res.perPage)
        return MangasPage(mangas, hasNextPage)
    }

    private fun parseHtmlMangasPage(response: Response): MangasPage {
        val doc = response.asJsoup()
        val galleries = doc.select(".gallery, .gallery-item")
        val mangas = galleries.mapNotNull { element ->
            val link = element.selectFirst("a[href*=/g/]") ?: element.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            val id = Regex("""/g/(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val img = element.selectFirst("img")
            val rawTitle = element.selectFirst(".caption")?.text()?.trim()
                ?: link.selectFirst(".caption")?.text()?.trim()
                ?: img?.attr("alt")?.trim()
                ?: element.selectFirst("a > div")?.text()?.trim()
                ?: link.selectFirst("div")?.text()?.trim()
                ?: "Gallery $id"
            val thumb = img?.let {
                it.attr("data-src").ifEmpty {
                    it.attr("src").ifEmpty {
                        it.attr("data-original").ifEmpty {
                            it.attr("srcset").substringBefore(" ")
                        }
                    }
                }
            } ?: ""
            val fullThumb = when {
                thumb.startsWith("//") -> "https:$thumb"
                thumb.startsWith("/") -> "$baseUrl$thumb"
                else -> thumb
            }
            SManga.create().apply {
                url = "/g/$id/"
                title = if (displayFullTitle) rawTitle else rawTitle.shortenTitle()
                thumbnail_url = fullThumb
                status = SManga.COMPLETED
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
        }
        val hasNextPage = doc.select(".pagination .next, a.next, .pagination a:contains(>)").isNotEmpty()
        return MangasPage(mangas.distinctBy { it.url }, hasNextPage)
    }

    private fun parseHtmlMangaDetails(doc: Document, id: String): SManga {
        val fullTitle = doc.select("#info > h1").text().replace("\"", "").trim()
        val japTitle = doc.select("#info > h2").text().replace("\"", "").trim()
        val prettyTitle = doc.select("#info > h3").text().replace("\"", "").trim()
        val displayTitle = when {
            displayFullTitle && fullTitle.isNotEmpty() -> fullTitle
            displayFullTitle && japTitle.isNotEmpty() -> japTitle
            prettyTitle.isNotEmpty() -> prettyTitle
            fullTitle.isNotEmpty() -> fullTitle.shortenTitle()
            else -> japTitle.shortenTitle()
        }
        val coverImg = doc.select("#cover img")
        val coverUrl = coverImg.attr("data-src").ifEmpty { coverImg.attr("src") }.let {
            if (it.startsWith("//")) "https:$it" else it
        }

        val artists = doc.select(".tag-container:contains(Artists) .name").joinToString { it.text() }
        val groups = doc.select(".tag-container:contains(Groups) .name").joinToString { it.text() }
        val tags = doc.select(".tag-container:contains(Tags) .name").joinToString { it.text() }
        val numPages = doc.select(".tag-container:contains(Pages) .name").text()
        val favorites = doc.select("#info i.fa-heart + span span").text().removeSurrounding("(", ")")

        return SManga.create().apply {
            url = "/g/$id/"
            title = displayTitle
            thumbnail_url = coverUrl
            status = SManga.COMPLETED
            artist = artists
            author = groups.ifEmpty { artists }
            description = buildString {
                append("Full English and Japanese titles:\n")
                append(fullTitle.ifEmpty { japTitle }, "\n")
                if (japTitle.isNotEmpty() && japTitle != fullTitle) {
                    append(japTitle, "\n")
                }
                append("\n")
                if (numPages.isNotEmpty()) append("Pages: ", numPages, "\n")
                if (favorites.isNotEmpty()) append("Favorited by: ", favorites, "\n")
                val categories = doc.select(".tag-container:contains(Categories) .name").joinToString { it.text() }
                if (categories.isNotEmpty()) append("Categories: ", categories, "\n")
                val parodies = doc.select(".tag-container:contains(Parodies) .name").joinToString { it.text() }
                if (parodies.isNotEmpty()) append("Parodies: ", parodies, "\n")
                val characters = doc.select(".tag-container:contains(Characters) .name").joinToString { it.text() }
                if (characters.isNotEmpty()) append("Characters: ", characters, "\n")
            }
            genre = tags
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            initialized = true
        }
    }

    private fun parseHtmlPages(doc: Document): List<Page> {
        val thumbs = doc.select("div.thumbs .thumb-container img, div.thumbs img, .gallerythumb img")
        if (thumbs.isNotEmpty()) {
            return thumbs.mapIndexed { i, img ->
                val src = img.attr("data-src").ifEmpty { img.attr("src") }
                Page(i, imageUrl = convertThumbToImageUrl(src))
            }
        }

        for (script in doc.select("script")) {
            val data = script.data()
            val match = Regex("""window\._gallery\s*=\s*(?:JSON\.parse\()?(["{].*?)(?:\);|\)[\s;]|$)""", RegexOption.DOT_MATCHES_ALL).find(data)
            if (match != null) {
                try {
                    val jsonStr = match.groupValues[1].let {
                        if (it.startsWith("\"")) {
                            it.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
                        } else {
                            it
                        }
                    }
                    val mediaId = Regex(""""media_id"\s*:\s*"(\d+)"""").find(jsonStr)?.groupValues?.get(1)
                    val pagesJson = Regex(""""pages"\s*:\s*\[(.*?)\]""").find(jsonStr)?.groupValues?.get(1)
                    val pageTypes = if (pagesJson != null) {
                        Regex(""""t"\s*:\s*"([a-z])"""").findAll(pagesJson).map { it.groupValues[1] }.toList()
                    } else {
                        emptyList()
                    }
                    if (mediaId != null && pageTypes.isNotEmpty()) {
                        return pageTypes.mapIndexed { i, t ->
                            val ext = when (t) {
                                "j" -> "jpg"
                                "p" -> "png"
                                "w" -> "webp"
                                "g" -> "gif"
                                else -> "jpg"
                            }
                            Page(i, imageUrl = "https://i.nhentai.net/galleries/$mediaId/${i + 1}.$ext")
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return emptyList()
    }

    private fun convertThumbToImageUrl(thumbUrl: String): String {
        var url = if (thumbUrl.startsWith("//")) "https:$thumbUrl" else thumbUrl
        url = url.replace("://t.", "://i.")
            .replace("://t1.", "://i1.")
            .replace("://t2.", "://i2.")
            .replace("://t3.", "://i3.")
            .replace("://t4.", "://i4.")
        url = url.replace(Regex("""/(\d+)t\.([a-zA-Z0-9]+)$"""), "/$1.$2")
        return url
    }

    private fun SharedPreferences.parseRateLimit(): Pair<Int, Long> {
        val raw = getString(RATE_LIMIT_PREF, RATE_LIMIT_DEFAULT).orEmpty()
        return parseRateLimitString(raw) ?: defaultRateLimit()
    }

    private fun defaultRateLimit(): Pair<Int, Long> = requireNotNull(parseRateLimitString(RATE_LIMIT_DEFAULT))

    private fun parseRateLimitString(raw: String): Pair<Int, Long>? {
        val parts = raw.split("/", limit = 2)
        if (parts.size != 2) return null

        val permits = parts[0].toIntOrNull()
        val period = parts[1].toLongOrNull()
        if (permits == null || period == null) return null

        return permits.coerceIn(RATE_LIMIT_MIN_PERMITS, RATE_LIMIT_MAX_PERMITS) to
            period.coerceIn(RATE_LIMIT_MIN_PERIOD_SECONDS, RATE_LIMIT_MAX_PERIOD_SECONDS)
    }

    private class NhGalleryCacheInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            if (!GALLERY_PATH_REGEX.matches(response.request.url.encodedPath)) return response

            return response.newBuilder()
                .removeHeader("Cache-Control")
                .removeHeader("Expires")
                .removeHeader("Pragma")
                .header("Cache-Control", "max-age=$GALLERY_CACHE_MAX_AGE_SECONDS")
                .build()
        }
    }

    private inner class NhApiRetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url

            if (url.host != baseUrlHost || !API_PATH_REGEX.matches(url.encodedPath)) {
                return chain.proceed(request)
            }

            val response = chain.proceed(request)
            if (response.code != 429 || request.header(BACKOFF_RETRY_HEADER) != null) {
                return response
            }

            val retryAfter = response.header("Retry-After")
            if (!retryAfter.isNullOrEmpty() && retryAfter.toLongOrNull() != 0L) return response

            response.close()
            val retryRequest = request.newBuilder()
                .header(BACKOFF_RETRY_HEADER, "1")
                .build()
            return chain.proceed(retryRequest)
        }
    }

    private inner class NhAuthorizationInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            val url = request.url

            if (url.host != baseUrlHost || !API_PATH_REGEX.matches(url.encodedPath)) {
                return chain.proceed(request)
            }

            if (!apiKey.isNullOrBlank()) {
                request = request.newBuilder().addHeader("Authorization", "Key $apiKey").build()
                val response = chain.proceed(request)
                if (response.code == 401) {
                    response.close()
                    throw IOException("Invalid API key")
                }
                return response
            }

            if (url.encodedPath.contains("/favorites")) {
                val accessToken = cookieToken
                if (accessToken.isNotEmpty()) {
                    request = request.newBuilder().addHeader("Authorization", "User $accessToken").build()
                }
                val response = chain.proceed(request)
                if (response.code == 401) {
                    response.close()
                    throw IOException("Log in via WebView or add API key in the settings to view favorites")
                }
                return response
            }

            return chain.proceed(request)
        }
    }

    companion object {
        const val API_KEY = "api_key"
        const val PREFIX_ID_SEARCH = "id:"
        private val GALLERY_PATH_REGEX = Regex("^/api/v2/galleries/\\d+/?$")
        private val API_PATH_REGEX = Regex("^/api/v2/.*$")
        private const val BACKOFF_RETRY_HEADER = "X-NHentai-Backoff-Retry"
        private const val GALLERY_CACHE_MAX_AGE_SECONDS = 7200
        private const val RATE_LIMIT_PREF = "rate_limit_pref"
        private const val RATE_LIMIT_DEFAULT = "1/1"
        private const val RATE_LIMIT_MIN_PERMITS = 1
        private const val RATE_LIMIT_MAX_PERMITS = 10
        private const val RATE_LIMIT_MIN_PERIOD_SECONDS = 1L
        private const val RATE_LIMIT_MAX_PERIOD_SECONDS = 60L
        private val RATE_LIMIT_OPTIONS = arrayOf(
            Pair("0.25 rps", "1/4"),
            Pair("0.5 rps", "1/2"),
            Pair("1 rps (default)", "1/1"),
            Pair("2 rps", "2/1"),
            Pair("4 rps (recommended with API key)", "4/1"),
        )
        private const val TITLE_PREF = "Display manga title as:"
        private const val SORT_PREF = "Default sort preference when searching"
    }
}

// ============================== DTOs & Models ==============================

@Serializable
class NHConfig(
    @SerialName("image_servers") val imageServers: List<String>,
    @SerialName("thumb_servers") val thumbServers: List<String>,
)

@Serializable
class PaginatedResponse<T>(
    val result: List<T> = listOf(),
    @SerialName("per_page") val perPage: Int = 0,
    @SerialName("num_pages") val numPages: Int? = null,
    val total: Int? = null,
)

@Serializable
class GalleryItem(
    private val id: Int,
    private val thumbnail: String? = null,
    @SerialName("media_id") private val mediaId: String? = null,
    private val title: Title? = null,
    @SerialName("english_title") private val englishTitle: String? = null,
    @SerialName("japanese_title") private val japaneseTitle: String? = null,
) {
    fun toSMangaOrNull(thumbServer: String, fullTitle: Boolean): SManga? {
        val rawTitle = englishTitle
            ?: japaneseTitle
            ?: title?.english
            ?: title?.japanese
            ?: title?.pretty
            ?: return null
        val thumbPath = thumbnail ?: mediaId?.let { "galleries/$it/thumb.webp" } ?: return null
        val host = thumbServer.removeSuffix("/")
        val path = thumbPath.removePrefix("/")
        return SManga.create().apply {
            url = "/g/$id/"
            title = if (fullTitle) rawTitle else rawTitle.shortenTitle()
            thumbnail_url = "$host/$path"
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
    }
}

@Serializable
class Hentai(
    private val id: Int,
    val pages: List<Image> = emptyList(),
    private val thumbnail: Image,
    val tags: List<Tag>,
    private val title: Title,
    @SerialName("upload_date") private val uploadDate: Long,
    @SerialName("num_favorites") private val numFavorites: Long,
    @SerialName("num_pages") private val numPages: Int,
) {
    fun toSManga(thumbServer: String, fullTitle: Boolean) = SManga.create().apply {
        val titles = this@Hentai.title
        url = "/g/$id/"
        title = if (fullTitle) {
            titles.english ?: titles.japanese ?: titles.pretty!!
        } else {
            titles.pretty ?: (titles.english ?: titles.japanese)!!.shortenTitle()
        }
        val host = thumbServer.removeSuffix("/")
        val path = thumbnail.path.removePrefix("/")
        thumbnail_url = "$host/$path"
        status = SManga.COMPLETED
        artist = getArtists(this@Hentai)
        author = getGroups(this@Hentai) ?: getArtists(this@Hentai)
        description = buildString {
            append("Full English and Japanese titles:\n")
            append("${titles.english ?: titles.japanese ?: titles.pretty ?: ""}\n")
            append(titles.japanese ?: "")
            append("\n\n")
            append("Pages: $numPages\n")
            append("Favorited by: $numFavorites\n")
            append(getTagDescription(this@Hentai))
        }
        genre = getTags(this@Hentai)
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    fun toSChapter() = SChapter.create().apply {
        name = "Chapter"
        scanlator = getGroups(this@Hentai)
        date_upload = uploadDate * 1000
        url = "/g/$id/"
    }
}

@Serializable
class Title(
    val english: String? = null,
    val japanese: String? = null,
    val pretty: String? = null,
)

@Serializable
class Image(
    val path: String,
)

@Serializable
class Tag(
    val name: String,
    val type: String,
)

// ============================== Utils ==============================

private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")

fun String.shortenTitle() = replace(shortenTitleRegex, "").trim()

fun getArtists(data: Hentai): String {
    val artists = data.tags.filter { it.type == "artist" }
    return artists.joinToString { it.name }
}

fun getGroups(data: Hentai): String? {
    val groups = data.tags.filter { it.type == "group" }
    return groups.joinToString { it.name }.takeIf { it.isNotEmpty() }
}

fun getTagDescription(data: Hentai): String {
    val tags = data.tags.groupBy { it.type }
    return buildString {
        tags["category"]?.joinToString { it.name }?.let {
            append("Categories: ", it, "\n")
        }
        tags["parody"]?.joinToString { it.name }?.let {
            append("Parodies: ", it, "\n")
        }
        tags["character"]?.joinToString { it.name }?.let {
            append("Characters: ", it, "\n")
        }
        append("\n")
    }
}

fun getTags(data: Hentai): String {
    val tags = data.tags.filter { it.type == "tag" }
    return tags.map { it.name }.sorted().joinToString()
}
