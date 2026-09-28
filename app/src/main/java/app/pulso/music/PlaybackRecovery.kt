package app.pulso.music

/** A budget lasts until stable playback or an explicit new queue. Never loop through repeat-all. */
internal class PlaybackRecovery {
    enum class Action { RETRY, NEXT, STOP, OFFLINE }
    private val retried = mutableSetOf<String>()
    private val failed = mutableSetOf<String>()

    fun reset() { retried.clear(); failed.clear() }
    fun decide(id: String, remote: Boolean, online: Boolean, nextId: String?): Action {
        if (remote && !online) return Action.OFFLINE
        if (remote && retried.add(id)) return Action.RETRY
        failed.add(id)
        return if (failed.size < 3 && nextId != null && nextId != id && nextId !in failed)
            Action.NEXT else Action.STOP
    }
}

object PlaybackFeedback {
    val message = kotlinx.coroutines.flow.MutableStateFlow("")
}
