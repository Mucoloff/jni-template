package dev.sweety.nativegen.spi

import dev.sweety.nativeapi.Cabi
import dev.sweety.nativeapi.Core
import dev.sweety.nativeapi.Jni
import dev.sweety.nativeapi.Marshal

/**
 * Intermediate representation of one native method, parsed from a {@code @NativeApi}
 * interface. Shared between the core processor and any strategy/emitter plugins.
 * The helper methods render the various lowered forms the emitters need.
 */
class NativeMethod(
    val name: String,
    val ret: NativeType,
    val params: List<NativeType>,
    val jni: Jni?,
    val cabi: Cabi?,
    /** Built-in generic engine strategy (DIRECT/SESSION_*), or null. */
    val strategy: Marshal.Op?,
    /** Custom plugin strategy id (from {@code @Strategy}), or null. */
    val customId: String?,
    val engineName: String,
    val ifaceMethod: Boolean,
    /** C-ABI symbol the JNI thunk routes to; null = the method's own @Cabi. */
    val target: String?,
    val core: Core.Op?,
) {
    fun loweredReturn() = ret.loweredJava()
    fun segmentReturn() = ret.segmentJava()
    fun loweredParams() = params { it.loweredJava() }
    fun segmentParams() = params { it.segmentJava() }

    private inline fun params(ty: (NativeType) -> String) =
        params.mapIndexed { i, k -> "${ty(k)} p$i" }.joinToString(", ")

    fun loweredArgs() = params.mapIndexed { i, k ->
        if (k == NativeType.PTR) "p$i.address()" else "p$i"
    }.joinToString(", ")

    fun engineParams() = engineParams(0)
    fun sessionEngineParams() = engineParams(1)

    private fun engineParams(from: Int) = params.drop(from).mapIndexed { idx, k ->
        val i = idx + from
        if (k == NativeType.PTR) "@NotNull MemorySegment p$i" else "${k.segmentJava()} p$i"
    }.joinToString(", ")

    fun sessionArgs() = (listOf("state") + (1 until params.size).map { "p$it" }).joinToString(", ")

    fun segmentArgs() = params.indices.joinToString(", ") { "p$it" }

    fun jniSig() = "(" + params.joinToString("") { it.jniSig() } + ")" + ret.jniSig()

    fun functionDescriptor(): String {
        val ps = params.joinToString(", ") { it.layout() }
        return if (ret == NativeType.VOID) "FunctionDescriptor.ofVoid($ps)"
        else "FunctionDescriptor.of(${ret.layout()}${if (ps.isEmpty()) "" else ", $ps"})"
    }
}

/** The lowered type kinds the framework understands at the binding boundary. */
enum class NativeType {
    VOID, PTR, LONG, INT, BYTE, BYTE_ARRAY, LONG_ARRAY;

    val isArray get() = this == BYTE_ARRAY || this == LONG_ARRAY

    fun loweredJava() = when (this) {
        VOID -> "void"; PTR, LONG -> "long"; INT -> "int"; BYTE -> "byte"
        BYTE_ARRAY -> "byte[]"; LONG_ARRAY -> "long[]"
    }

    fun jniSig() = when (this) {
        VOID -> "V"; PTR, LONG -> "J"; INT -> "I"; BYTE -> "B"; BYTE_ARRAY -> "[B"; LONG_ARRAY -> "[J"
    }

    fun segmentJava() = when (this) {
        VOID -> "void"; PTR -> "MemorySegment"; LONG -> "long"; INT -> "int"; BYTE -> "byte"
        BYTE_ARRAY -> "byte[]"; LONG_ARRAY -> "long[]"
    }

    fun layout() = when (this) {
        PTR -> "ADDRESS"; LONG -> "JAVA_LONG"; INT -> "JAVA_INT"; BYTE -> "JAVA_BYTE"
        else -> error("no layout for $this")
    }

    /** Lowercased name for the native-api.json (consumed by the C++/Rust renderers). */
    fun kindName() = when (this) {
        VOID -> "void"; PTR -> "ptr"; LONG -> "long"; INT -> "int"; BYTE -> "byte"
        BYTE_ARRAY -> "byteArray"; LONG_ARRAY -> "longArray"
    }
}
