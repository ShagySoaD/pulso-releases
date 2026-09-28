package app.pulso.music

data class EngineReady(val version: String, val updateWarning: String? = null)

/** An unavailable update must not disable a working bundled installation. */
object EngineStartup {
    fun start(verify: () -> String, update: (() -> Unit)? = null): EngineReady {
        val bundled = verify()
        if (update == null) return EngineReady(bundled)
        return try {
            update()
            EngineReady(verify())
        } catch (error: Exception) {
            // Confirm the previous executable still works, rather than merely assuming it does.
            EngineReady(verify(), error.message ?: error.javaClass.simpleName)
        }
    }
}
