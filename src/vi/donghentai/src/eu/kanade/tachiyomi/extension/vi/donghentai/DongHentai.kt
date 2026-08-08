package eu.kanade.tachiyomi.extension.vi.donghentai

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@Source
abstract class DongHentai : HttpSource() {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(5)
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request {
        return GET(buildListUrl(page, POPULAR_SORT), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return parseMangaList(response)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(buildListUrl(page, LATEST_SORT), headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return parseMangaList(response)
    }

    // ================================ Search ===============================

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", LATEST_SORT)
            .addQueryParameter("page", page.toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("filter[name]", query)
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return parseMangaList(response)
    }

    private fun buildListUrl(page: Int, sort: String): String {
        return "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .build()
            .toString()
    }

    private fun parseMangaList(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("div.manga-vertical").mapNotNull { element ->
            val titleElement = element.selectFirst("h3 a") ?: return@mapNotNull null

            SManga.create().apply {
                title = titleElement.text()

                setUrlWithoutDomain(
                    titleElement.absUrl("href"),
                )

                val imageElement = element.selectFirst("div.cover-frame img")

                thumbnail_url = imageElement?.let {
                    it.absUrl("src")
                        .ifEmpty { it.absUrl("data-src") }
                        .ifEmpty { null }
                }
            }
        }

        val hasNextPage =
            document.selectFirst(
                "nav[aria-label=Pagination] a[aria-label=Next]",
            ) != null

        return MangasPage(
            mangas = mangas,
            hasNextPage = hasNextPage,
        )
    }

    // =============================== Details ===============================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        return SManga.create().apply {

            title = document
                .selectFirst("h1.text-xl.ml-1, h1.text-xl")
                ?.text()
                ?: ""

            val imageElement = document.selectFirst("div.cover-frame img")

            thumbnail_url = imageElement?.let {
                it.absUrl("src")
                    .ifEmpty { it.absUrl("data-src") }
                    .ifEmpty { null }
            }

            author = document
                .selectFirst("span:containsOwn(Author:) + span a")
                ?.text()

            genre = document
                .select("#genres-list a")
                .joinToString { it.text() }
                .ifEmpty { null }

            status = parseStatus(
                document
                    .selectFirst("span:containsOwn(Tình trạng:)")
                    ?.parent()
                    ?.select("span")
                    ?.last()
                    ?.text(),
            )

            description = document
                .selectFirst(
                    "div.prose.dark\\:prose-invert.max-w-none",
                )
                ?.text()
                ?.ifEmpty { null }
        }
    }

    private fun parseStatus(statusText: String?): Int {
        return when {
            statusText?.contains(
                "Đang tiến hành",
                ignoreCase = true,
            ) == true -> SManga.ONGOING

            statusText?.contains(
                "Hoàn thành",
                ignoreCase = true,
            ) == true -> SManga.COMPLETED

            else -> SManga.UNKNOWN
        }
    }

    // ============================== Chapters ===============================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document
            .select("#chapterList > a.block")
            .mapNotNull { element ->

                val url = element.absUrl("href")

                if (url.isBlank()) {
                    return@mapNotNull null
                }

                SChapter.create().apply {
                    setUrlWithoutDomain(url)

                    name = element
                        .selectFirst("div.grow span")
                        ?.text()
                        ?: element.text()

                    date_upload = parseChapterDate(
                        element
                            .selectFirst(
                                "span.ml-2.whitespace-nowrap",
                            )
                            ?.text(),
                    )
                }
            }
    }

    private fun parseChapterDate(dateStr: String?): Long {
        return parseRelativeDate(dateStr)
            .takeIf { it != 0L }
            ?: DATE_FORMAT.tryParse(dateStr)
    }

    private fun parseRelativeDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) {
            return 0L
        }

        val calendar = Calendar.getInstance(
            TimeZone.getTimeZone("Asia/Ho_Chi_Minh"),
            Locale.ROOT,
        )

        val number = NUMBER_REGEX
            .find(dateStr)
            ?.value
            ?.toIntOrNull()
            ?: return 0L

        when {
            dateStr.contains("giây", ignoreCase = true) ->
                calendar.add(Calendar.SECOND, -number)

            dateStr.contains("phút", ignoreCase = true) ->
                calendar.add(Calendar.MINUTE, -number)

            dateStr.contains("giờ", ignoreCase = true) ->
                calendar.add(Calendar.HOUR_OF_DAY, -number)

            dateStr.contains("ngày", ignoreCase = true) ->
                calendar.add(Calendar.DAY_OF_MONTH, -number)

            dateStr.contains("tuần", ignoreCase = true) ->
                calendar.add(Calendar.WEEK_OF_YEAR, -number)

            dateStr.contains("tháng", ignoreCase = true) ->
                calendar.add(Calendar.MONTH, -number)

            dateStr.contains("năm", ignoreCase = true) ->
                calendar.add(Calendar.YEAR, -number)

            else -> return 0L
        }

        return calendar.timeInMillis
    }

    // ================================ Pages ================================

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val images = document
            .select("#chapter-content img.chapter-img")
            .ifEmpty {
                document.select("#chapter-content img")
            }

        return images
            .mapIndexedNotNull { index, element ->

                val imageUrl = element
                    .absUrl("data-src")
                    .ifEmpty {
                        element.absUrl("src")
                    }
                    .replace("\r", "")
                    .trim()
                    .ifEmpty {
                        null
                    }
                    ?: return@mapIndexedNotNull null

                Page(
                    index = index,
                    imageUrl = imageUrl,
                )
            }
            .distinctBy { it.imageUrl }
    }

    override fun imageUrlParse(response: Response): String {
        throw UnsupportedOperationException()
    }

    // ============================== Filters ================================

    override fun getFilterList(): FilterList {
        return FilterList()
    }

    companion object {
        private const val LATEST_SORT = "-updated_at"
        private const val POPULAR_SORT = "-views"

        private val NUMBER_REGEX = Regex("\\d+")

        private val DATE_FORMAT by lazy {
            SimpleDateFormat(
                "dd/MM/yyyy",
                Locale.ROOT,
            ).apply {
                timeZone = TimeZone.getTimeZone(
                    "Asia/Ho_Chi_Minh",
                )
            }
        }
    }
}
