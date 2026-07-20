plugins {
    alias(kei.plugins.multisrc)
}

dependencies {
    api(project(":lib:cookieinterceptor"))
}

keiyoushi {
    baseVersionCode = 1
    libVersion = "1.4"
}
