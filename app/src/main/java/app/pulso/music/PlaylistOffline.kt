package app.pulso.music

object PlaylistOffline {
    fun toDownload(tracks: List<Track>, active: Set<String> = emptySet()) = tracks.distinctBy { it.id }
        .filter { !it.id.startsWith("local-") && it.localUri.isBlank() && it.id !in active }
    fun toDelete(tracks: List<Track>) = tracks.distinctBy { it.id }
        .filter { !it.id.startsWith("local-") && it.localUri.isNotBlank() }
}
