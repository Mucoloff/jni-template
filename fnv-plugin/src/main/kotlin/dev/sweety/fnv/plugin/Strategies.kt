package dev.sweety.fnv.plugin

import dev.sweety.nativeapi.Marshal
import dev.sweety.nativegen.spi.MarshalStrategy
import dev.sweety.nativegen.spi.NativeMethod

/**
 * `@Strategy(id = "heap")`: a heap `byte[]` convenience. JNI calls the native byte[] entrypoint
 * directly; FFM copies into a confined arena then calls the DIRECT segment method of the same name.
 * Bodies use fully-qualified names so the generic engine template needs no FNV-specific imports.
 */
class HeapStrategy : MarshalStrategy {
    override fun handles(id: String) = id == "heap"

    override fun emit(method: NativeMethod, binding: String, all: List<NativeMethod>): String? = when (binding) {
        "jni" -> if (method.jni == null) null else
            (if (method.ifaceMethod) "    @Override\n" else "") +
                "    public ${method.segmentReturn()} ${method.engineName}(byte @NotNull [] data) {\n" +
                "        return bindings.${method.name}(data);\n" +
                "    }"

        "ffm" -> if (!method.ifaceMethod) null else {
            val direct = all.first { it.strategy == Marshal.Op.DIRECT && it.name == method.engineName }
            "    @Override\n" +
                "    public ${method.segmentReturn()} ${method.engineName}(byte @NotNull [] data) {\n" +
                "        try (java.lang.foreign.Arena a = java.lang.foreign.Arena.ofConfined()) {\n" +
                "            return bindings.${direct.name}(dev.sweety.mem.NativeArena.copyOf(a, data), data.length);\n" +
                "        }\n" +
                "    }"
        }

        else -> null
    }
}

/**
 * `@Strategy(id = "batch")`: hash many segments in one crossing. JNI extracts addresses into a
 * `long[]`; FFM marshals ptrs/lens/out into off-heap arrays. Two native forms (array vs raw): the
 * JNI form is emitted for the binding that has `@Jni`, the FFM form for the one that has `@Cabi`.
 */
class BatchStrategy : MarshalStrategy {
    override fun handles(id: String) = id == "batch"

    override fun emit(method: NativeMethod, binding: String, all: List<NativeMethod>): String? {
        val jni = binding == "jni"
        if (jni && method.jni == null) return null
        if (!jni && method.cabi == null) return null
        val sig = "long @NotNull [] ${method.engineName}(@NotNull MemorySegment @NotNull [] data, long @NotNull [] lens)"
        return if (jni) {
            "    @Override\n" +
                "    public $sig {\n" +
                "        long[] addrs = new long[data.length];\n" +
                "        for (int i = 0; i < data.length; i++) addrs[i] = data[i].address();\n" +
                "        return bindings.${method.name}(addrs, lens);\n" +
                "    }"
        } else {
            "    @Override\n" +
                "    public $sig {\n" +
                "        int n = data.length;\n" +
                "        try (java.lang.foreign.Arena a = java.lang.foreign.Arena.ofConfined()) {\n" +
                "            MemorySegment ptrs = a.allocate(java.lang.foreign.ValueLayout.ADDRESS.byteSize() * n);\n" +
                "            MemorySegment lensSeg = a.allocate(java.lang.foreign.ValueLayout.JAVA_LONG.byteSize() * n);\n" +
                "            MemorySegment out = a.allocate(java.lang.foreign.ValueLayout.JAVA_LONG.byteSize() * n);\n" +
                "            for (int i = 0; i < n; i++) {\n" +
                "                ptrs.setAtIndex(java.lang.foreign.ValueLayout.ADDRESS, i, data[i]);\n" +
                "                lensSeg.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG, i, lens[i]);\n" +
                "            }\n" +
                "            bindings.${method.name}(ptrs, lensSeg, out, n);\n" +
                "            long[] result = new long[n];\n" +
                "            for (int i = 0; i < n; i++) result[i] = out.getAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG, i);\n" +
                "            return result;\n" +
                "        }\n" +
                "    }"
        }
    }
}
