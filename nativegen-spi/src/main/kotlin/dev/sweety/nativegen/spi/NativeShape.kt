package dev.sweety.nativegen.spi

/**
 * Extension point for the <em>native</em> side: emits the body of a JNI thunk whose argument
 * shape the framework core does not know (the native counterpart of {@link MarshalStrategy}).
 * Methods tagged {@code @Strategy(id = "...")} that need a non-plain thunk are rendered by the
 * {@code NativeShape} registered for that id and target language, discovered via
 * {@link java.util.ServiceLoader} on the KSP classpath. The core emits the generic PLAIN
 * thunks, the C-ABI lifecycle, the RegisterNatives table and JNI_OnLoad itself.
 */
interface NativeShape {
    /** True if this renders thunks of the given {@code @Strategy(id)} in [language] ("cpp"/"rust"). */
    fun handles(id: String, language: String): Boolean

    /**
     * Source of the JNI thunk function for [method] in [language]. The thunk routes through the
     * flat C-ABI symbol [method].target; it reads its registration name from {@code method.jni.thunk}.
     */
    fun emitThunk(method: NativeMethod, language: String): String
}
