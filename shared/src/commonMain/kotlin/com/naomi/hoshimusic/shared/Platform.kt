package com.naomi.hoshimusic.shared

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
