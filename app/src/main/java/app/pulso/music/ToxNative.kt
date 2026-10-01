package app.pulso.music

@androidx.annotation.Keep
internal object ToxNative {
    init { System.loadLibrary("pulso_tox") }
    external fun create(saved: ByteArray): Long
    external fun close(handle: Long)
    external fun iterate(handle: Long, listener: Any): Int
    external fun save(handle: Long): ByteArray
    external fun address(handle: Long): ByteArray
    external fun name(handle: Long, name: ByteArray)
    external fun add(handle: Long, address: ByteArray, accept: Boolean): Long
    external fun find(handle: Long, key: ByteArray): Long
    external fun key(handle: Long, friend: Long): ByteArray?
    external fun remove(handle: Long, friend: Long): Boolean
    external fun send(handle: Long, friend: Long, message: ByteArray): Long
    external fun packet(handle: Long, friend: Long, message: ByteArray): Boolean
    external fun bootstrap(handle: Long, host: String, port: Int, key: ByteArray, tcp: Boolean)
}
