package com.naomi.hoshimusic.domain

import com.naomi.hoshimusic.data.Song

data class MusicGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val songs: List<Song>,
)

object MusicGrouping {

    fun albumGroups(songs: List<Song>): List<MusicGroup> = songs.groupBy { it.album }.map { (album, tracks) ->
        MusicGroup(album, album, "${tracks.first().albumArtist} • ${tracks.size} lagu", tracks.sortedBy(Song::trackNumber))
    }.sortedBy { it.title.lowercase() }

    fun artistGroups(songs: List<Song>): List<MusicGroup> = songs.groupBy { it.albumArtist.ifBlank { it.artist } }.map { (artist, tracks) ->
        MusicGroup(artist, artist, "${tracks.map(Song::album).distinct().size} album • ${tracks.size} lagu", tracks.sortedBy { it.title.lowercase() })
    }.sortedBy { it.title.lowercase() }

    fun genreGroups(songs: List<Song>): List<MusicGroup> = songs.groupBy { it.genre }.map { (genre, tracks) ->
        MusicGroup(genre, genre, "${tracks.size} lagu", tracks.sortedBy { it.title.lowercase() })
    }.sortedBy { it.title.lowercase() }

    fun folderGroups(songs: List<Song>): List<MusicGroup> = songs.groupBy { it.folder }.map { (folder, tracks) ->
        MusicGroup(folder, folder.substringAfterLast('/'), "$folder • ${tracks.size} lagu", tracks.sortedBy { it.title.lowercase() })
    }.sortedBy { it.title.lowercase() }
}
