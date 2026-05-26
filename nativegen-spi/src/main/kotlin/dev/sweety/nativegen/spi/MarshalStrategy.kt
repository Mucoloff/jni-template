package dev.sweety.nativegen.spi

/**
 * Extension point: emits the body of a custom engine-layer method whose marshalling the
 * framework core does not know (e.g. heap-array convenience, batch). A method opts in with
 * {@code @Strategy(id = "...")}; the core, instead of hardcoding shapes, looks up the
 * implementation registered for that id via {@link java.util.ServiceLoader} on the KSP
 * classpath. The framework ships only the generic DIRECT/SESSION handling internally.
 */
interface MarshalStrategy {
    /** True if this strategy renders the given {@code @Strategy(id)}. */
    fun handles(id: String): Boolean

    /**
     * Java source of the engine method for [method] on [binding] ("jni" or "ffm"), or null
     * if this method contributes nothing on that binding (e.g. a JNI-only form under FFM).
     * [all] is the full method set, for resolving related methods (e.g. the DIRECT call a
     * heap-copy path delegates to). The generated impl exposes the binding via a `bindings`
     * field of the concrete bindings type.
     */
    fun emit(method: NativeMethod, binding: String, all: List<NativeMethod>): String?
}
