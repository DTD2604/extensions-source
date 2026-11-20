import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "NHentai"
    versionCode = 11
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    listOf("all", "en", "ja", "zh", "vi").forEach { sourceLang ->
        source {
            lang = sourceLang
            baseUrl = "https://nhentai.net"
            when (sourceLang) {
                "all" -> id = 7309872737163460316L
                "en" -> id = 3122156392225024195L
                "ja" -> id = 4726175775739752699L
                "zh" -> id = 2203215402871965477L
                "vi" -> id = 9165839893600661480L
            }
        }
    }

    deeplink {
        host("nhentai.net")
        path("/g/..*")
    }
}
