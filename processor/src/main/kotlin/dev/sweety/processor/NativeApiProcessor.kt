package dev.sweety.processor

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import dev.sweety.nativeapi.Cabi
import dev.sweety.nativeapi.Engine
import dev.sweety.nativeapi.Jni
import dev.sweety.nativeapi.Marshal
import dev.sweety.nativeapi.NativeApi
import dev.sweety.nativeapi.Ptr
import java.nio.file.Files
import java.nio.file.Path

/**
 * KSP port of the native-API code generator. From one `@NativeApi` interface (declared at the
 * MemorySegment level) it generates, visibly to the Kotlin compiler:
 *
 *  * `RawNatives` + per-backend holder classes, the `native-api.json` descriptor;
 *  * `JniBindings` / `FfmBindings` (+ the shared `Bindings` interface);
 *  * the generic `HashEngineBase<B>` and, when `@Engine` names them, the full concrete
 *    `JniHashEngine` / `FfmHashEngine` and the `EngineFactory` (the last is why this is KSP and
 *    not a javac processor — it must be visible to Kotlin's `HashEngine.of`).
 *
 * Descriptor path comes from the KSP option `native.descriptor`.
 */
class NativeApiProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        NativeApiProcessor(environment.codeGenerator, environment.logger, environment.options)
}

@OptIn(KspExperimental::class)
class NativeApiProcessor(
    private val codeGen: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {

    private var done = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (done) return emptyList()
        val symbols = resolver.getSymbolsWithAnnotation(NativeApi::class.qualifiedName!!)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (symbols.isEmpty()) return emptyList()
        done = true
        for (iface in symbols) handle(iface)
        return emptyList()
    }

    private fun handle(iface: KSClassDeclaration) {
        val pkg = iface.packageName.asString()
        val api = iface.getAnnotationsByType(NativeApi::class).first()
        val enums = api.backendEnums.toList()
        val names = enums.map { it.substring(0, 1).uppercase() + it.substring(1).lowercase() }

        val methods = iface.getDeclaredFunctions().mapNotNull { parse(it) }.toList()
        val jni = methods.filter { it.jni != null }
        val ffm = methods.filter { it.cabi != null }
        val holders = names.map { "${pkg.replace('.', '/')}/${it}Natives" }

        val engine = iface.getAnnotationsByType(Engine::class).firstOrNull()

        writeRawNatives(pkg, jni)
        names.forEachIndexed { i, n -> writeHolder(pkg, "${n}Natives", enums[i], jni) }
        writeJniBindings(pkg, names, enums, jni, engine != null)
        writeFfmBindings(pkg, ffm, engine != null)
        writeDescriptor(names, holders, jni)

        if (engine != null) {
            val common = methods.filter {
                it.strategy != null && it.jni != null && it.cabi != null && isEngineRole(it.strategy)
            }
            writeBindings(pkg, common)
            writeEngineBase(pkg, engine, common)
            if (engine.jniImpl.isNotEmpty()) writeEngineImpl(pkg, engine, methods, jni = true)
            if (engine.ffmImpl.isNotEmpty()) writeEngineImpl(pkg, engine, methods, jni = false)
            if (engine.jniImpl.isNotEmpty() && engine.ffmImpl.isNotEmpty()) writeEngineFactory(engine)
        }
    }

    private fun isEngineRole(s: Marshal.Strategy) =
        s != Marshal.Strategy.HEAP_HASH && s != Marshal.Strategy.BATCH

    // --- generation (text-block templates with %placeholder% substitution) -------

    private fun writeRawNatives(pkg: String, jni: List<Method>) = write(
        pkg, "RawNatives", "java", RAW_NATIVES
            .replace("%pkg%", pkg)
            .replace("%methods%", join(jni) { "    ${it.loweredReturn()} ${it.name}(${it.loweredParams()});" })
    )

    private fun writeHolder(pkg: String, cls: String, backendEnum: String, jni: List<Method>) = write(
        pkg, cls, "java", HOLDER
            .replace("%pkg%", pkg)
            .replace("%cls%", cls)
            .replace("%enum%", backendEnum)
            .replace("%methods%", join(jni) {
                "    @Override public native ${it.loweredReturn()} ${it.name}(${it.loweredParams()});"
            })
    )

    private fun writeJniBindings(pkg: String, names: List<String>, enums: List<String>, jni: List<Method>, engine: Boolean) {
        val cases = names.indices.joinToString("\n") {
            "            case ${enums[it]} -> ${names[it]}Natives.get();"
        }
        write(pkg, "JniBindings", "java", JNI_BINDINGS
            .replace("%pkg%", pkg)
            .replace("%impl%", if (engine) " implements Bindings" else "")
            .replace("%cases%", cases)
            .replace("%wrappers%", join(jni) {
                "    public ${it.segmentReturn()} ${it.name}(${it.segmentParams()}) {\n" +
                    "        ${jniBody(it)}\n" +
                    "    }"
            }))
    }

    private fun writeFfmBindings(pkg: String, ffm: List<Method>, engine: Boolean) = write(
        pkg, "FfmBindings", "java", FFM_BINDINGS
            .replace("%pkg%", pkg)
            .replace("%impl%", if (engine) " implements Bindings" else "")
            .replace("%fields%", join(ffm) { "    private final MethodHandle mh_${it.name};" })
            .replace("%inits%", join(ffm) {
                "        this.mh_${it.name} = down(lib, \"${it.cabi!!.value}\", ${it.functionDescriptor()});"
            })
            .replace("%wrappers%", join(ffm) {
                "    public ${it.segmentReturn()} ${it.name}(${it.segmentParams()}) {\n" +
                    "        try {\n" +
                    "            ${ffmCall(it)}\n" +
                    "        } catch (Throwable t) {\n" +
                    "            throw wrap(t);\n" +
                    "        }\n" +
                    "    }"
            })
    )

    private fun writeDescriptor(names: List<String>, holders: List<String>, jni: List<Method>) {
        val path = options["native.descriptor"]
        if (path == null) {
            logger.warn("native.descriptor option not set; skipping JSON descriptor")
            return
        }
        val backends = names.indices.joinToString(",\n") {
            "    {\"name\": \"${names[it]}\", \"holder\": \"${holders[it]}\"}"
        }
        val methods = join(jni, ",\n") {
            "    {\"name\": \"${it.name}\", \"sig\": \"${it.jniSig()}\", \"thunk\": \"${it.jni!!.thunk}\", \"critical\": ${it.jni.critical}}"
        }
        val json = DESCRIPTOR.replace("%backends%", backends).replace("%methods%", methods)
        val out = Path.of(path)
        out.parent?.let { Files.createDirectories(it) }
        Files.writeString(out, json)
        logger.warn("wrote native descriptor: $out")
    }

    private fun writeBindings(pkg: String, common: List<Method>) = write(
        pkg, "Bindings", "java", BINDINGS_IFACE
            .replace("%pkg%", pkg)
            .replace("%methods%", join(common) { "    ${it.segmentReturn()} ${it.name}(${it.segmentParams()});" })
    )

    private fun writeEngineBase(pkg: String, engine: Engine, common: List<Method>) {
        val roles = common.filter { it.strategy != Marshal.Strategy.DIRECT }.associateBy { it.strategy }
        val direct = common.filter { it.strategy == Marshal.Strategy.DIRECT }
        val create = roles[Marshal.Strategy.SESSION_CREATE]
        val free = roles[Marshal.Strategy.SESSION_FREE]
        val reset = roles[Marshal.Strategy.SESSION_RESET]
        if (create == null || free == null || reset == null) {
            logger.error("@Engine requires SESSION_CREATE/FREE/RESET methods")
            return
        }

        val directMethods = join(direct) {
            "    @Override public ${it.segmentReturn()} ${it.name}(${it.engineParams()}) {\n" +
                "        ${if (it.ret == Kind.VOID) "" else "return "}bindings.${it.name}(${it.segmentArgs()});\n" +
                "    }"
        }
        val sessionMethods = listOf(
            Marshal.Strategy.SESSION_UPDATE, Marshal.Strategy.SESSION_DIGEST, Marshal.Strategy.SESSION_RESET
        ).mapNotNull { roles[it] }.joinToString("\n") {
            "        @Override public ${it.segmentReturn()} ${it.name}(${it.sessionEngineParams()}) {\n" +
                "            ${if (it.ret == Kind.VOID) "" else "return "}bindings.${it.name}(${it.sessionArgs()});\n" +
                "        }"
        }

        write(pkg, engine.baseSuffix, "java", ENGINE_BASE
            .replace("%pkg%", pkg)
            .replace("%iface%", engine.iface)
            .replace("%session%", engine.session)
            .replace("%ifaceName%", simpleName(engine.iface))
            .replace("%sessionName%", simpleName(engine.session))
            .replace("%base%", engine.baseSuffix)
            .replace("%create%", create.name)
            .replace("%reset%", reset.name)
            .replace("%free%", free.name)
            .replace("%poolSize%", engine.poolSize.toString())
            .replace("%direct%", directMethods)
            .replace("%sessionMethods%", sessionMethods))
    }

    private fun writeEngineImpl(pkg: String, engine: Engine, methods: List<Method>, jni: Boolean) {
        val implFqn = if (jni) engine.jniImpl else engine.ffmImpl
        val bindings = if (jni) "JniBindings" else "FfmBindings"

        val heap = buildString {
            for (m in methods) {
                if (m.strategy != Marshal.Strategy.HEAP_HASH) continue
                if (jni) {
                    if (m.jni == null) continue
                    append(heapJni(m))
                } else {
                    if (!m.ifaceMethod) continue // FFM exposes only the interface heap method (copy path)
                    append(heapFfm(m, methods))
                }
                append("\n\n")
            }
        }.trimEnd()

        write(packageOf(implFqn), simpleName(implFqn), "java", (if (jni) JNI_ENGINE else FFM_ENGINE)
            .replace("%pkg%", packageOf(implFqn))
            .replace("%cls%", simpleName(implFqn))
            .replace("%baseFqn%", "$pkg.${engine.baseSuffix}")
            .replace("%bindingsFqn%", "$pkg.$bindings")
            .replace("%heap%", heap)
            .replace("%batch%", if (jni) batchJni(methods) else batchFfm(methods)))
    }

    private fun writeEngineFactory(engine: Engine) {
        val pkg = packageOf(engine.iface)
        write(pkg, "EngineFactory", "java", ENGINE_FACTORY
            .replace("%pkg%", pkg)
            .replace("%iface%", simpleName(engine.iface))
            .replace("%jniFqn%", engine.jniImpl)
            .replace("%ffmFqn%", engine.ffmImpl))
    }

    private fun heapJni(m: Method) =
        (if (m.ifaceMethod) "    @Override\n" else "") +
            "    public ${m.segmentReturn()} ${m.engineName}(byte @NotNull [] data) {\n" +
            "        return bindings.${m.name}(data);\n" +
            "    }"

    private fun heapFfm(m: Method, methods: List<Method>): String {
        val direct = methods.firstOrNull { it.strategy == Marshal.Strategy.DIRECT && it.name == m.engineName }
            ?: error("HEAP_HASH engine '${m.engineName}' needs a DIRECT method of that name")
        return "    @Override\n" +
            "    public ${m.segmentReturn()} ${m.engineName}(byte @NotNull [] data) {\n" +
            "        try (Arena a = Arena.ofConfined()) {\n" +
            "            return bindings.${direct.name}(NativeArena.copyOf(a, data), data.length);\n" +
            "        }\n" +
            "    }"
    }

    private fun batch(methods: List<Method>, jniForm: Boolean) = methods.first {
        it.strategy == Marshal.Strategy.BATCH && (if (jniForm) it.jni != null else it.cabi != null)
    }

    private fun batchJni(methods: List<Method>): String {
        val b = batch(methods, true)
        return "    @Override\n" +
            "    public long @NotNull [] ${b.engineName}(@NotNull MemorySegment @NotNull [] data, long @NotNull [] lens) {\n" +
            "        long[] addrs = new long[data.length];\n" +
            "        for (int i = 0; i < data.length; i++) addrs[i] = data[i].address();\n" +
            "        return bindings.${b.name}(addrs, lens);\n" +
            "    }"
    }

    private fun batchFfm(methods: List<Method>): String {
        val b = batch(methods, false)
        return "    @Override\n" +
            "    public long @NotNull [] ${b.engineName}(@NotNull MemorySegment @NotNull [] data, long @NotNull [] lens) {\n" +
            "        int n = data.length;\n" +
            "        try (Arena a = Arena.ofConfined()) {\n" +
            "            MemorySegment ptrs = a.allocate(ADDRESS.byteSize() * n);\n" +
            "            MemorySegment lensSeg = a.allocate(JAVA_LONG.byteSize() * n);\n" +
            "            MemorySegment out = a.allocate(JAVA_LONG.byteSize() * n);\n" +
            "            for (int i = 0; i < n; i++) {\n" +
            "                ptrs.setAtIndex(ADDRESS, i, data[i]);\n" +
            "                lensSeg.setAtIndex(JAVA_LONG, i, lens[i]);\n" +
            "            }\n" +
            "            bindings.${b.name}(ptrs, lensSeg, out, n);\n" +
            "            long[] result = new long[n];\n" +
            "            for (int i = 0; i < n; i++) result[i] = out.getAtIndex(JAVA_LONG, i);\n" +
            "            return result;\n" +
            "        }\n" +
            "    }"
    }

    private fun jniBody(m: Method): String {
        val call = "h.${m.name}(${m.loweredArgs()})"
        return when (m.ret) {
            Kind.VOID -> "$call;"
            Kind.PTR -> "return MemorySegment.ofAddress($call);"
            else -> "return $call;"
        }
    }

    private fun ffmCall(m: Method): String {
        val invoke = "mh_${m.name}.invokeExact(${m.segmentArgs()})"
        return when (m.ret) {
            Kind.VOID -> "$invoke;"
            Kind.PTR -> "return (MemorySegment) $invoke;"
            Kind.LONG -> "return (long) $invoke;"
            Kind.INT -> "return (int) $invoke;"
            Kind.BYTE -> "return (byte) $invoke;"
            else -> error("unsupported FFM return for ${m.name}")
        }
    }

    private fun write(pkg: String, name: String, ext: String, body: String) {
        codeGen.createNewFile(Dependencies(false), pkg, name, ext).use { it.write(body.toByteArray()) }
    }

    // --- parsing / validation ----------------------------------------------------

    private fun parse(fn: KSFunctionDeclaration): Method? {
        val jni = fn.getAnnotationsByType(Jni::class).firstOrNull()
        val cabi = fn.getAnnotationsByType(Cabi::class).firstOrNull()
        if (jni == null && cabi == null) {
            logger.error("method needs @Jni and/or @Cabi", fn)
            return null
        }
        val retPtr = fn.getAnnotationsByType(Ptr::class).any()
        val ret = classify(fn, fn.returnType, retPtr)
        val params = fn.parameters.map { classify(it, it.type, it.getAnnotationsByType(Ptr::class).any()) }

        val marshal = fn.getAnnotationsByType(Marshal::class).firstOrNull()
        val name = fn.simpleName.asString()
        val engineName = marshal?.engine?.ifEmpty { name } ?: name
        val ifaceMethod = marshal?.iface ?: true

        if (cabi != null) {
            if (params.any { it.isArray }) logger.error("@Cabi method cannot take arrays (FFM is pointer-based): $name", fn)
            if (ret.isArray) logger.error("@Cabi method cannot return an array: $name", fn)
        }
        return Method(name, ret, params, jni, cabi, marshal?.value, engineName, ifaceMethod)
    }

    private fun classify(where: KSAnnotated, type: KSTypeReference?, ptr: Boolean): Kind {
        val qn = type?.resolve()?.declaration?.qualifiedName?.asString()
        if (ptr) {
            if (qn != SEGMENT) logger.error("@Ptr only applies to MemorySegment", where)
            return Kind.PTR
        }
        return when (qn) {
            "kotlin.Unit" -> Kind.VOID
            "kotlin.Long", "java.lang.Long" -> Kind.LONG
            "kotlin.Int", "java.lang.Integer" -> Kind.INT
            "kotlin.Byte", "java.lang.Byte" -> Kind.BYTE
            "kotlin.ByteArray" -> Kind.BYTE_ARRAY
            "kotlin.LongArray" -> Kind.LONG_ARRAY
            SEGMENT -> {
                logger.error("MemorySegment parameter/return must be annotated @Ptr", where); Kind.PTR
            }
            else -> {
                logger.error("unsupported type: $qn", where); Kind.LONG
            }
        }
    }

    private fun join(ms: List<Method>, sep: String = "\n", f: (Method) -> String) =
        ms.joinToString(sep, transform = f)

    private fun simpleName(fqn: String) = fqn.substringAfterLast('.')
    private fun packageOf(fqn: String) = if ('.' in fqn) fqn.substringBeforeLast('.') else ""

    private enum class Kind {
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
    }

    private inner class Method(
        val name: String,
        val ret: Kind,
        val params: List<Kind>,
        val jni: Jni?,
        val cabi: Cabi?,
        val strategy: Marshal.Strategy?,
        val engineName: String,
        val ifaceMethod: Boolean,
    ) {
        fun loweredReturn() = ret.loweredJava()
        fun segmentReturn() = ret.segmentJava()
        fun loweredParams() = params { it.loweredJava() }
        fun segmentParams() = params { it.segmentJava() }

        private inline fun params(ty: (Kind) -> String) =
            params.mapIndexed { i, k -> "${ty(k)} p$i" }.joinToString(", ")

        fun loweredArgs() = params.mapIndexed { i, k ->
            if (k == Kind.PTR) "p$i.address()" else "p$i"
        }.joinToString(", ")

        fun engineParams() = engineParams(0)
        fun sessionEngineParams() = engineParams(1)

        private fun engineParams(from: Int) = params.drop(from).mapIndexed { idx, k ->
            val i = idx + from
            if (k == Kind.PTR) "@NotNull MemorySegment p$i" else "${k.segmentJava()} p$i"
        }.joinToString(", ")

        fun sessionArgs() = (listOf("state") + (1 until params.size).map { "p$it" }).joinToString(", ")

        fun segmentArgs() = params.indices.joinToString(", ") { "p$it" }

        fun jniSig() = "(" + params.joinToString("") { it.jniSig() } + ")" + ret.jniSig()

        fun functionDescriptor(): String {
            val ps = params.joinToString(", ") { it.layout() }
            return if (ret == Kind.VOID) "FunctionDescriptor.ofVoid($ps)"
            else "FunctionDescriptor.of(${ret.layout()}${if (ps.isEmpty()) "" else ", $ps"})"
        }
    }

    companion object {
        private const val SEGMENT = "java.lang.foreign.MemorySegment"

        private val RAW_NATIVES = """
            package %pkg%;

            // Generated by NativeApiProcessor. Do not edit.
            interface RawNatives {
            %methods%
            }
            """.trimIndent() + "\n"

        private val HOLDER = """
            package %pkg%;

            import dev.sweety.Backend;
            import dev.sweety.NativeLib;

            // Generated by NativeApiProcessor. Do not edit.
            final class %cls% implements RawNatives {
                static { NativeLib.loadForJni(Backend.%enum%); }
                private static final %cls% INSTANCE = new %cls%();

                public static %cls% get() {
                    return INSTANCE;
                }

                private %cls%() {}

            %methods%
            }
            """.trimIndent() + "\n"

        private val JNI_BINDINGS = """
            package %pkg%;

            import dev.sweety.Backend;
            import java.lang.foreign.MemorySegment;

            // Generated by NativeApiProcessor. Do not edit. MemorySegment<->address glue over a JNI holder.
            public final class JniBindings%impl% {
                private final RawNatives h;
                private JniBindings(RawNatives h) { this.h = h; }

                public static JniBindings of(Backend backend) {
                    return new JniBindings(switch (backend) {
            %cases%
                    });
                }

            %wrappers%
            }
            """.trimIndent() + "\n"

        private val FFM_BINDINGS = """
            package %pkg%;

            import dev.sweety.Backend;
            import dev.sweety.NativeLib;
            import java.lang.foreign.Arena;
            import java.lang.foreign.FunctionDescriptor;
            import java.lang.foreign.Linker;
            import java.lang.foreign.MemorySegment;
            import java.lang.foreign.SymbolLookup;
            import java.lang.invoke.MethodHandle;
            import static java.lang.foreign.ValueLayout.*;

            // Generated by NativeApiProcessor. Do not edit. Cached FFM downcall handles.
            public final class FfmBindings%impl% {
                private static final Linker LINKER = Linker.nativeLinker();
            %fields%

                public FfmBindings(Backend backend) {
                    SymbolLookup lib = SymbolLookup.libraryLookup(NativeLib.libraryPath(backend), Arena.global());
            %inits%
                }

                private static MethodHandle down(SymbolLookup lib, String name, FunctionDescriptor fd) {
                    return LINKER.downcallHandle(
                            lib.find(name).orElseThrow(() -> new IllegalStateException("missing symbol " + name)), fd);
                }

                private static RuntimeException wrap(Throwable t) {
                    return t instanceof RuntimeException re ? re : new RuntimeException(t);
                }

            %wrappers%
            }
            """.trimIndent() + "\n"

        private val DESCRIPTOR = """
            {
              "backends": [
            %backends%
              ],
              "methods": [
            %methods%
              ]
            }
            """.trimIndent() + "\n"

        private val BINDINGS_IFACE = """
            package %pkg%;

            import java.lang.foreign.MemorySegment;

            // Generated by NativeApiProcessor. Do not edit. Segment-level surface shared by every binding.
            public interface Bindings {
            %methods%
            }
            """.trimIndent() + "\n"

        private val ENGINE_BASE = """
            package %pkg%;

            import dev.sweety.Backend;
            import dev.sweety.Binding;
            import %iface%;
            import %session%;
            import dev.sweety.pool.ObjectPool;
            import org.jetbrains.annotations.NotNull;
            import java.lang.foreign.MemorySegment;

            // Generated by NativeApiProcessor. Do not edit. DIRECT + SESSION half of the %ifaceName% impl,
            // shared across bindings via the generic Bindings parameter B.
            public abstract class %base%<B extends Bindings> implements %ifaceName% {
                protected final Backend backend;
                protected final B bindings;
                private final Binding binding;
                protected final ObjectPool<Session> sessions;

                protected %base%(B bindings, Backend backend, Binding binding) {
                    this.bindings = bindings;
                    this.backend = backend;
                    this.binding = binding;
                    this.sessions = ObjectPool.threadLocal(
                            () -> new Session(bindings.%create%()),
                            s -> bindings.%reset%(s.state),
                            s -> bindings.%free%(s.state),
                            %poolSize%);
                }

            %direct%

                @Override public @NotNull %sessionName% open() { return sessions.acquire(); }
                @Override public @NotNull Backend backend() { return backend; }
                @Override public @NotNull Binding binding() { return binding; }

                final class Session implements %sessionName% {
                    final MemorySegment state;
                    Session(MemorySegment state) { this.state = state; }
            %sessionMethods%
                    @Override public void close() { sessions.release(this); }
                }
            }
            """.trimIndent() + "\n"

        private val JNI_ENGINE = """
            package %pkg%;

            import dev.sweety.Backend;
            import dev.sweety.Binding;
            import org.jetbrains.annotations.NotNull;
            import java.lang.foreign.MemorySegment;

            // Generated by NativeApiProcessor. Do not edit. Full JNI engine impl (HEAP_HASH + BATCH).
            public final class %cls% extends %baseFqn%<%bindingsFqn%> {
                public %cls%(Backend backend) {
                    super(%bindingsFqn%.of(backend), backend, Binding.JNI);
                }

            %heap%

            %batch%
            }
            """.trimIndent() + "\n"

        private val FFM_ENGINE = """
            package %pkg%;

            import dev.sweety.Backend;
            import dev.sweety.Binding;
            import dev.sweety.mem.NativeArena;
            import org.jetbrains.annotations.NotNull;
            import java.lang.foreign.Arena;
            import java.lang.foreign.MemorySegment;
            import static java.lang.foreign.ValueLayout.ADDRESS;
            import static java.lang.foreign.ValueLayout.JAVA_LONG;

            // Generated by NativeApiProcessor. Do not edit. Full FFM engine impl (HEAP_HASH + BATCH).
            public final class %cls% extends %baseFqn%<%bindingsFqn%> {
                public %cls%(Backend backend) {
                    super(new %bindingsFqn%(backend), backend, Binding.FFM);
                }

            %heap%

            %batch%
            }
            """.trimIndent() + "\n"

        private val ENGINE_FACTORY = """
            package %pkg%;

            // Generated by NativeApiProcessor. Do not edit. Bridges Binding to the generated engine impls.
            public final class EngineFactory {
                private EngineFactory() {}

                public static %iface% of(Binding binding, Backend backend) {
                    return switch (binding) {
                        case JNI -> new %jniFqn%(backend);
                        case FFM -> new %ffmFqn%(backend);
                    };
                }
            }
            """.trimIndent() + "\n"
    }
}
