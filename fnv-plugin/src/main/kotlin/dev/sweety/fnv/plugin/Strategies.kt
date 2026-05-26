package dev.sweety.fnv.plugin

import dev.sweety.nativeapi.Marshal
import dev.sweety.nativegen.spi.MarshalStrategy
import dev.sweety.nativegen.spi.NativeMethod
import dev.sweety.nativegen.spi.NativeShape

/**
 * `@Strategy(id = "heap")`: a heap `byte[]` convenience. JNI calls the native byte[] entrypoint
 * directly; FFM copies into a confined arena then calls the DIRECT segment method of the same name.
 * Bodies use fully-qualified names so the generic engine template needs no FNV-specific imports.
 */
class HeapStrategy : MarshalStrategy, NativeShape {
    override fun handles(id: String) = id == "heap"

    // --- native side (JNI thunk) ---
    override fun handles(id: String, language: String) = id == "heap" && (language == "cpp" || language == "rust")

    override fun emitThunk(method: NativeMethod, language: String): String {
        val t = method.target!!
        val thunk = method.jni!!.thunk
        val crit = method.jni!!.critical
        return when (language) {
            "cpp" -> {
                val get = if (crit) "void* b = env->GetPrimitiveArrayCritical(a0, nullptr);"
                else "jbyte* b = env->GetByteArrayElements(a0, nullptr);"
                val rel = if (crit) "env->ReleasePrimitiveArrayCritical(a0, b, JNI_ABORT);"
                else "env->ReleaseByteArrayElements(a0, b, JNI_ABORT);"
                "    jlong $thunk(JNIEnv* env, jclass, jbyteArray a0) {\n" +
                        "        jsize len = env->GetArrayLength(a0);\n" +
                        "        $get\n" +
                        "        if (!b) return 0;\n" +
                        "        jlong r = static_cast<jlong>($t(reinterpret_cast<void*>(b), static_cast<size_t>(len)));\n" +
                        "        $rel\n" +
                        "        return r;\n" +
                        "    }"
            }

            "rust" -> if (crit)
                "unsafe extern \"system\" fn $thunk(mut env: JNIEnv, _c: JClass, arr: JByteArray) -> jlong {\n" +
                        "    match env.get_array_elements_critical(&arr, ReleaseMode::NoCopyBack) {\n" +
                        "        Ok(e) => $t(e.as_ptr() as *mut c_void, e.len() as usize) as jlong,\n" +
                        "        Err(_) => 0,\n" +
                        "    }\n}"
            else
                "unsafe extern \"system\" fn $thunk(env: JNIEnv, _c: JClass, arr: JByteArray) -> jlong {\n" +
                        "    match env.convert_byte_array(&arr) {\n" +
                        "        Ok(v) => $t(v.as_ptr() as *mut c_void, v.len() as usize) as jlong,\n" +
                        "        Err(_) => 0,\n" +
                        "    }\n}"

            else -> error("heap: unsupported language $language")
        }
    }

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
class BatchStrategy : MarshalStrategy, NativeShape {
    override fun handles(id: String) = id == "batch"

    // --- native side (JNI thunk) ---
    override fun handles(id: String, language: String) = id == "batch" && (language == "cpp" || language == "rust")

    override fun emitThunk(method: NativeMethod, language: String): String {
        val t = method.target!!
        val thunk = method.jni!!.thunk
        return when (language) {
            "cpp" -> "    jlongArray $thunk(JNIEnv* env, jclass, jlongArray a0, jlongArray a1) {\n" +
                    "        jsize n = env->GetArrayLength(a0);\n" +
                    "        jlong* p = env->GetLongArrayElements(a0, nullptr);\n" +
                    "        jlong* l = env->GetLongArrayElements(a1, nullptr);\n" +
                    "        jlongArray out = env->NewLongArray(n);\n" +
                    "        if (p && l && out) {\n" +
                    "            jlong* o = env->GetLongArrayElements(out, nullptr);\n" +
                    "            $t(reinterpret_cast<void*>(p), reinterpret_cast<void*>(l), reinterpret_cast<void*>(o), static_cast<size_t>(n));\n" +
                    "            env->ReleaseLongArrayElements(out, o, 0);\n" +
                    "        }\n" +
                    "        if (p) env->ReleaseLongArrayElements(a0, p, JNI_ABORT);\n" +
                    "        if (l) env->ReleaseLongArrayElements(a1, l, JNI_ABORT);\n" +
                    "        return out;\n" +
                    "    }"

            "rust" -> "unsafe extern \"system\" fn $thunk<'l>(env: JNIEnv<'l>, _c: JClass<'l>, addrs: JLongArray<'l>, lens: JLongArray<'l>) -> jlong {\n" +
                    "    let n = env.get_array_length(&addrs).unwrap_or(0);\n" +
                    "    let mut a = vec![0i64; n as usize];\n" +
                    "    let mut l = vec![0i64; n as usize];\n" +
                    "    let _ = env.get_long_array_region(&addrs, 0, &mut a);\n" +
                    "    let _ = env.get_long_array_region(&lens, 0, &mut l);\n" +
                    "    let mut out = vec![0i64; n as usize];\n" +
                    "    $t(a.as_ptr() as *mut c_void, l.as_ptr() as *mut c_void, out.as_mut_ptr() as *mut c_void, n as usize);\n" +
                    "    let arr = env.new_long_array(n).unwrap();\n" +
                    "    let _ = env.set_long_array_region(&arr, 0, &out);\n" +
                    "    arr.into_raw() as jlong\n}"

            else -> error("batch: unsupported language $language")
        }
    }

    override fun emit(method: NativeMethod, binding: String, all: List<NativeMethod>): String? {
        val jni = binding == "jni"
        if (jni && method.jni == null) return null
        if (!jni && method.cabi == null) return null
        val sig =
            "long @NotNull [] ${method.engineName}(@NotNull MemorySegment @NotNull [] data, long @NotNull [] lens)"
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
