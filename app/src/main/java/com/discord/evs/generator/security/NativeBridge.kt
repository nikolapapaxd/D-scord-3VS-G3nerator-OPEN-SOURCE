package com.discord.evs.generator.security

/**
 * JNI bridge to the optional Go-built native library (libevs_core.so).
 *
 * Build the .so with the Go module under native/go (see native/go/README.md),
 * then place ABI folders under app/src/main/jniLibs/:
 *   jniLibs/arm64-v8a/libevs_core.so
 *   jniLibs/armeabi-v7a/libevs_core.so
 *
 * Until the .so is present, [available] is false and callers use Kotlin fallbacks.
 * This does NOT stop decompilation of android/androidx — nothing can.
 * It moves *your* sensitive helpers into harder-to-read native code.
 */
object NativeBridge {

    @Volatile
    var available: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("evs_core")
            available = nativeProbe()
        } catch (_: UnsatisfiedLinkError) {
            available = false
        } catch (_: Throwable) {
            available = false
        }
    }

    /** Returns true if native layer initialized. */
    @JvmStatic
    private external fun nativeProbe(): Boolean

    /**
     * Optional: HMAC-SHA256 in native code so the exact payload layout
     * is not obvious in the DEX.
     * Returns lowercase hex, or empty if native unavailable.
     */
    @JvmStatic
    external fun nativeHmacSha256(key: String, message: String): String

    /**
     * Optional: package + signature quick check in native.
     */
    @JvmStatic
    external fun nativeIntegrityHint(packageName: String, sigSha256Hex: String): Boolean
}
