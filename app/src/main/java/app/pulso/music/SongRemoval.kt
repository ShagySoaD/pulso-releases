package app.pulso.music

/** Do not lose the library reference when Android refuses to delete the audio. */
object SongRemoval {
    fun remove(track: Track, deleteAudio: (Track) -> Unit, forget: (String) -> Unit) {
        if (track.localUri.isNotBlank()) deleteAudio(track)
        forget(track.id)
    }
}
