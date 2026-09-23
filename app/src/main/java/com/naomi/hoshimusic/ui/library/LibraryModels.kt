package com.naomi.hoshimusic.ui.library

import com.naomi.hoshimusic.domain.MusicGroup

internal typealias SongGroup = MusicGroup

internal enum class LibraryFilter(val label: String) {
    Songs("Lagu"),
    Albums("Album"),
    Artists("Artis"),
    Playlists("Playlist"),
    Genres("Genre"),
    Folders("Folder"),
    Favorites("Favorit"),
    Activity("Aktivitas"),
}
