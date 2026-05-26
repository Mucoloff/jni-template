package dev.sweety.processor;

import dev.sweety.nativeapi.Cabi;
import dev.sweety.nativeapi.Engine;
import dev.sweety.nativeapi.Jni;
import dev.sweety.nativeapi.Marshal;
import dev.sweety.nativeapi.NativeApi;
import dev.sweety.nativeapi.Ptr;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * From one {@code @NativeApi} interface (declared at the MemorySegment level),
 * generates the whole binding plumbing for both bindings:
 *
 * <ul>
 *   <li>{@code RawNatives} (lowered JNI interface, addresses as {@code long}) and the
 *       per-backend holder classes implementing it with {@code native} methods;</li>
 *   <li>{@code native-api.json} (name + JNI signature + thunk) for the C++/Rust tables;</li>
 *   <li>{@code JniBindings} — MemorySegment-level adapter that delegates to a holder,
 *       converting {@code segment.address()};</li>
 *   <li>{@code FfmBindings} — cached downcall handles + {@code invokeExact} wrappers for
 *       every {@code @Cabi} method.</li>
 * </ul>
 * <p>
 * Pass the JSON descriptor path with {@code -Anative.descriptor=<file>}.
 */
@SupportedAnnotationTypes("dev.sweety.nativeapi.NativeApi")
@SupportedOptions(NativeApiProcessor.OPT_DESCRIPTOR)
public final class NativeApiProcessor extends AbstractProcessor {

    static final String OPT_DESCRIPTOR = "native.descriptor";
    private static final String SEGMENT = "java.lang.foreign.MemorySegment";

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (Element e : round.getElementsAnnotatedWith(NativeApi.class)) {
            if (e.getKind() != ElementKind.INTERFACE) {
                error(e, "@NativeApi only applies to interfaces");
                continue;
            }
            try {
                handle((TypeElement) e);
            } catch (IOException ex) {
                error(e, "code generation failed: " + ex.getMessage());
            }
        }
        return true;
    }

    private void handle(TypeElement iface) throws IOException {
        String pkg = processingEnv.getElementUtils().getPackageOf(iface).getQualifiedName().toString();
        NativeApi api = iface.getAnnotation(NativeApi.class);
        String[] enums = api.backendEnums();
        // Holder name derived from the enum constant: CPP -> Cpp, RUST -> Rust.
        String[] names = new String[enums.length];
        for (int i = 0; i < enums.length; i++) {
            String e = enums[i];
            names[i] = e.substring(0, 1).toUpperCase() + e.substring(1).toLowerCase();
        }

        List<Method> methods = new ArrayList<>();
        Set<String> thunks = new HashSet<>();
        for (Element m : iface.getEnclosedElements()) {
            if (m.getKind() != ElementKind.METHOD) continue;
            Method method = parse((ExecutableElement) m);
            if (method == null) continue; // error already reported
            if (method.jni != null && !thunks.add(method.jni.thunk())) {
                error(m, "duplicate @Jni thunk: " + method.jni.thunk());
            }
            methods.add(method);
        }

        List<Method> jni = methods.stream().filter(x -> x.jni != null).collect(Collectors.toList());
        List<Method> ffm = methods.stream().filter(x -> x.cabi != null).collect(Collectors.toList());

        List<String> holders = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            holders.add(pkg.replace('.', '/') + "/" + names[i] + "Natives");
        }

        Engine engine = iface.getAnnotation(Engine.class);
        boolean hasEngine = engine != null;

        writeRawNatives(pkg, jni);
        for (int i = 0; i < names.length; i++) writeHolder(pkg, names[i] + "Natives", enums[i], jni);
        writeJniBindings(pkg, names, enums, jni, hasEngine);
        writeFfmBindings(pkg, ffm, hasEngine);
        writeDescriptor(names, holders, jni);

        if (hasEngine) {
            // Methods on the binding surface shared by every binding (DIRECT + SESSION).
            List<Method> common = methods.stream()
                    .filter(m -> m.strategy != null && m.jni != null && m.cabi != null && isEngineRole(m.strategy))
                    .collect(Collectors.toList());
            writeBindings(pkg, common);
            writeEngineBase(pkg, engine, common);
            if (!engine.jniImpl().isEmpty()) writeEngineImpl(pkg, engine, methods, true);
            if (!engine.ffmImpl().isEmpty()) writeEngineImpl(pkg, engine, methods, false);
        }
    }

    private static boolean isEngineRole(Marshal.Strategy s) {
        return s != Marshal.Strategy.HEAP_HASH && s != Marshal.Strategy.BATCH;
    }

    // --- generation (text-block templates with %placeholder% substitution) -------

    private static final String RAW_NATIVES = """
            package %pkg%;
            
            // Generated by NativeApiProcessor. Do not edit.
            interface RawNatives {
            %methods%
            }
            """;

    private static final String HOLDER = """
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
            """;

    private static final String JNI_BINDINGS = """
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
            """;

    private static final String FFM_BINDINGS = """
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
            """;

    private static final String DESCRIPTOR = """
            {
              "backends": [
            %backends%
              ],
              "methods": [
            %methods%
              ]
            }
            """;

    private static final String BINDINGS_IFACE = """
            package %pkg%;
            
            import java.lang.foreign.MemorySegment;
            
            // Generated by NativeApiProcessor. Do not edit. Segment-level surface shared by every binding.
            public interface Bindings {
            %methods%
            }
            """;

    private static final String ENGINE_BASE = """
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
            """;

    /**
     * Segment-level interface implemented by every generated {@code *Bindings}.
     */
    private void writeBindings(String pkg, List<Method> common) throws IOException {
        write(pkg + ".Bindings", BINDINGS_IFACE
                .replace("%pkg%", pkg)
                .replace("%methods%", join(common, m ->
                        "    " + m.segmentReturn() + " " + m.name + "(" + m.segmentParams() + ");")));
    }

    /**
     * One generic {@code abstract} engine base over {@code B extends Bindings}: DIRECT + pooled SESSION.
     */
    private void writeEngineBase(String pkg, Engine engine, List<Method> common) throws IOException {
        Map<Marshal.Strategy, Method> roles = new EnumMap<>(Marshal.Strategy.class);
        List<Method> direct = new ArrayList<>();
        for (Method m : common) {
            if (m.strategy == Marshal.Strategy.DIRECT) direct.add(m);
            else roles.put(m.strategy, m);
        }
        Method create = roles.get(Marshal.Strategy.SESSION_CREATE);
        Method free = roles.get(Marshal.Strategy.SESSION_FREE);
        Method reset = roles.get(Marshal.Strategy.SESSION_RESET);
        if (create == null || free == null || reset == null) {
            error(null, "@Engine requires SESSION_CREATE/FREE/RESET methods");
            return;
        }

        String directMethods = join(direct, m ->
                "    @Override public " + m.segmentReturn() + " " + m.name + "(" + m.engineParams() + ") {\n"
                        + "        " + (m.ret == Kind.VOID ? "" : "return ") + "bindings." + m.name + "(" + m.segmentArgs() + ");\n"
                        + "    }");

        StringBuilder sm = new StringBuilder();
        for (Marshal.Strategy role : List.of(Marshal.Strategy.SESSION_UPDATE,
                Marshal.Strategy.SESSION_DIGEST, Marshal.Strategy.SESSION_RESET)) {
            Method m = roles.get(role);
            if (m == null) continue;
            if (!sm.isEmpty()) sm.append("\n");
            sm.append("        @Override public ").append(m.segmentReturn()).append(" ").append(m.name)
                    .append("(").append(m.sessionEngineParams()).append(") {\n")
                    .append("            ").append(m.ret == Kind.VOID ? "" : "return ")
                    .append("bindings.").append(m.name).append("(").append(m.sessionArgs()).append(");\n")
                    .append("        }");
        }

        write(pkg + "." + engine.baseSuffix(), ENGINE_BASE
                .replace("%pkg%", pkg)
                .replace("%iface%", engine.iface())
                .replace("%session%", engine.session())
                .replace("%ifaceName%", simpleName(engine.iface()))
                .replace("%sessionName%", simpleName(engine.session()))
                .replace("%base%", engine.baseSuffix())
                .replace("%create%", create.name)
                .replace("%reset%", reset.name)
                .replace("%free%", free.name)
                .replace("%poolSize%", Integer.toString(engine.poolSize()))
                .replace("%direct%", directMethods)
                .replace("%sessionMethods%", sm.toString()));
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static final String JNI_ENGINE = """
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
            """;

    private static final String FFM_ENGINE = """
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
            """;

    /**
     * The full concrete engine for one binding: the HEAP_HASH ({@code byte[]}) and BATCH
     * ({@code MemorySegment[]}) methods whose marshalling can't live in the generic base.
     */
    private void writeEngineImpl(String pkg, Engine engine, List<Method> methods, boolean jni) throws IOException {
        String implFqn = jni ? engine.jniImpl() : engine.ffmImpl();
        String bindings = jni ? "JniBindings" : "FfmBindings";

        StringBuilder heap = new StringBuilder();
        for (Method m : methods) {
            if (m.strategy() != Marshal.Strategy.HEAP_HASH) continue;
            if (jni) {
                if (m.jni() == null) continue;
                heap.append(heapJni(m));
            } else {
                if (!m.ifaceMethod()) continue; // FFM exposes only the interface heap method (copy path)
                heap.append(heapFfm(m, methods));
            }
            heap.append("\n\n");
        }

        write(implFqn, (jni ? JNI_ENGINE : FFM_ENGINE)
                .replace("%pkg%", packageOf(implFqn))
                .replace("%cls%", simpleName(implFqn))
                .replace("%baseFqn%", pkg + "." + engine.baseSuffix())
                .replace("%bindingsFqn%", pkg + "." + bindings)
                .replace("%heap%", heap.toString().stripTrailing())
                .replace("%batch%", jni ? batchJni(methods) : batchFfm(methods)));
    }

    /** JNI heap hash: delegate straight to the native {@code byte[]} entrypoint. */
    private static String heapJni(Method m) {
        return (m.ifaceMethod() ? "    @Override\n" : "")
                + "    public " + m.segmentReturn() + " " + m.engineName() + "(byte @NotNull [] data) {\n"
                + "        return bindings." + m.name() + "(data);\n"
                + "    }";
    }

    /** FFM heap hash: copy into a confined arena, then call the DIRECT segment hash of the same name. */
    private static String heapFfm(Method m, List<Method> methods) {
        String direct = methods.stream()
                .filter(x -> x.strategy() == Marshal.Strategy.DIRECT && x.name().equals(m.engineName()))
                .findFirst().orElseThrow(() ->
                        new IllegalStateException("HEAP_HASH engine '" + m.engineName() + "' needs a DIRECT method of that name"))
                .name();
        return "    @Override\n"
                + "    public " + m.segmentReturn() + " " + m.engineName() + "(byte @NotNull [] data) {\n"
                + "        try (Arena a = Arena.ofConfined()) {\n"
                + "            return bindings." + direct + "(NativeArena.copyOf(a, data), data.length);\n"
                + "        }\n"
                + "    }";
    }

    private static Method batch(List<Method> methods, boolean jniForm) {
        return methods.stream()
                .filter(m -> m.strategy() == Marshal.Strategy.BATCH && (jniForm ? m.jni() != null : m.cabi() != null))
                .findFirst().orElseThrow(() -> new IllegalStateException("missing BATCH method"));
    }

    /** JNI batch: extract segment addresses into a {@code long[]} and call the array native. */
    private static String batchJni(List<Method> methods) {
        Method b = batch(methods, true);
        return "    @Override\n"
                + "    public long @NotNull [] " + b.engineName() + "(@NotNull MemorySegment @NotNull [] data, long @NotNull [] lens) {\n"
                + "        long[] addrs = new long[data.length];\n"
                + "        for (int i = 0; i < data.length; i++) addrs[i] = data[i].address();\n"
                + "        return bindings." + b.name() + "(addrs, lens);\n"
                + "    }";
    }

    /** FFM batch: marshal segments + lengths + output into off-heap arrays for one crossing. */
    private static String batchFfm(List<Method> methods) {
        Method b = batch(methods, false);
        return "    @Override\n"
                + "    public long @NotNull [] " + b.engineName() + "(@NotNull MemorySegment @NotNull [] data, long @NotNull [] lens) {\n"
                + "        int n = data.length;\n"
                + "        try (Arena a = Arena.ofConfined()) {\n"
                + "            MemorySegment ptrs = a.allocate(ADDRESS.byteSize() * n);\n"
                + "            MemorySegment lensSeg = a.allocate(JAVA_LONG.byteSize() * n);\n"
                + "            MemorySegment out = a.allocate(JAVA_LONG.byteSize() * n);\n"
                + "            for (int i = 0; i < n; i++) {\n"
                + "                ptrs.setAtIndex(ADDRESS, i, data[i]);\n"
                + "                lensSeg.setAtIndex(JAVA_LONG, i, lens[i]);\n"
                + "            }\n"
                + "            bindings." + b.name() + "(ptrs, lensSeg, out, n);\n"
                + "            long[] result = new long[n];\n"
                + "            for (int i = 0; i < n; i++) result[i] = out.getAtIndex(JAVA_LONG, i);\n"
                + "            return result;\n"
                + "        }\n"
                + "    }";
    }

    private void writeRawNatives(String pkg, List<Method> jni) throws IOException {
        write(pkg + ".RawNatives", RAW_NATIVES
                .replace("%pkg%", pkg)
                .replace("%methods%", join(jni, m ->
                        "    " + m.loweredReturn() + " " + m.name + "(" + m.loweredParams() + ");")));
    }

    private void writeHolder(String pkg, String cls, String backendEnum, List<Method> jni) throws IOException {
        write(pkg + "." + cls, HOLDER
                .replace("%pkg%", pkg)
                .replace("%cls%", cls)
                .replace("%enum%", backendEnum)
                .replace("%methods%", join(jni, m ->
                        "    @Override public native " + m.loweredReturn() + " " + m.name + "(" + m.loweredParams() + ");")));
    }

    private void writeJniBindings(String pkg, String[] names, String[] enums, List<Method> jni, boolean engine) throws IOException {
        StringBuilder cases = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) cases.append("\n");
            cases.append("            case ").append(enums[i]).append(" -> ").append(names[i]).append("Natives.get();");
        }
        write(pkg + ".JniBindings", JNI_BINDINGS
                .replace("%pkg%", pkg)
                .replace("%impl%", engine ? " implements Bindings" : "")
                .replace("%cases%", cases.toString())
                .replace("%wrappers%", join(jni, m ->
                        "    public " + m.segmentReturn() + " " + m.name + "(" + m.segmentParams() + ") {\n"
                                + "        " + jniBody(m) + "\n"
                                + "    }")));
    }

    private void writeFfmBindings(String pkg, List<Method> ffm, boolean engine) throws IOException {
        write(pkg + ".FfmBindings", FFM_BINDINGS
                .replace("%pkg%", pkg)
                .replace("%impl%", engine ? " implements Bindings" : "")
                .replace("%fields%", join(ffm, m -> "    private final MethodHandle mh_" + m.name + ";"))
                .replace("%inits%", join(ffm, m -> "        this.mh_" + m.name + " = down(lib, \""
                        + m.cabi.value() + "\", " + m.functionDescriptor() + ");"))
                .replace("%wrappers%", join(ffm, m ->
                        "    public " + m.segmentReturn() + " " + m.name + "(" + m.segmentParams() + ") {\n"
                                + "        try {\n"
                                + "            " + ffmCall(m) + "\n"
                                + "        } catch (Throwable t) {\n"
                                + "            throw wrap(t);\n"
                                + "        }\n"
                                + "    }")));
    }

    private void writeDescriptor(String[] names, List<String> holders, List<Method> jni) throws IOException {
        String path = processingEnv.getOptions().get(OPT_DESCRIPTOR);
        if (path == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "-A" + OPT_DESCRIPTOR + " not set; skipping JSON descriptor");
            return;
        }
        StringBuilder backends = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) backends.append(",\n");
            backends.append("    {\"name\": \"").append(names[i]).append("\", \"holder\": \"")
                    .append(holders.get(i)).append("\"}");
        }
        String methods = join(jni, m -> "    {\"name\": \"" + m.name + "\", \"sig\": \"" + m.jniSig()
                + "\", \"thunk\": \"" + m.jni.thunk() + "\", \"critical\": " + m.jni.critical() + "}", ",\n");
        String json = DESCRIPTOR
                .replace("%backends%", backends.toString())
                .replace("%methods%", methods);
        Path out = Path.of(path);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        Files.writeString(out, json);
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, "wrote native descriptor: " + out);
    }

    /**
     * Single statement body of a JniBindings wrapper (delegates to the holder).
     */
    private static String jniBody(Method m) {
        String call = "h." + m.name + "(" + m.loweredArgs() + ")";
        return switch (m.ret) {
            case VOID -> call + ";";
            case PTR -> "return MemorySegment.ofAddress(" + call + ");";
            default -> "return " + call + ";";
        };
    }

    /**
     * Single statement inside the try of an FfmBindings wrapper (invokeExact).
     */
    private static String ffmCall(Method m) {
        String invoke = "mh_" + m.name + ".invokeExact(" + m.segmentArgs() + ")";
        return switch (m.ret) {
            case VOID -> invoke + ";";
            case PTR -> "return (MemorySegment) " + invoke + ";";
            case LONG -> "return (long) " + invoke + ";";
            case INT -> "return (int) " + invoke + ";";
            case BYTE -> "return (byte) " + invoke + ";";
            default -> throw new IllegalStateException("unsupported FFM return for " + m.name);
        };
    }

    private static String join(List<Method> ms, java.util.function.Function<Method, String> f) {
        return join(ms, f, "\n");
    }

    private static String join(List<Method> ms, java.util.function.Function<Method, String> f, String sep) {
        return ms.stream().map(f).collect(Collectors.joining(sep));
    }

    private void write(String fqcn, String body) throws IOException {
        JavaFileObject f = processingEnv.getFiler().createSourceFile(fqcn);
        try (Writer w = f.openWriter()) {
            w.write(body);
        }
    }

    // --- parsing / validation ----------------------------------------------------

    private Method parse(ExecutableElement ee) {
        Jni jni = ee.getAnnotation(Jni.class);
        Cabi cabi = ee.getAnnotation(Cabi.class);
        if (jni == null && cabi == null) {
            error(ee, "method needs @Jni and/or @Cabi");
            return null;
        }
        boolean retPtr = ee.getAnnotation(Ptr.class) != null;
        Kind ret = classify(ee, ee.getReturnType(), retPtr, true);

        List<Kind> params = new ArrayList<>();
        for (VariableElement p : ee.getParameters()) {
            boolean ptr = p.getAnnotation(Ptr.class) != null;
            params.add(classify(p, p.asType(), ptr, false));
        }
        Marshal marshal = ee.getAnnotation(Marshal.class);
        Marshal.Strategy strategy = marshal == null ? null : marshal.value();
        String name = ee.getSimpleName().toString();
        String engineName = (marshal == null || marshal.engine().isEmpty()) ? name : marshal.engine();
        boolean ifaceMethod = marshal == null || marshal.iface();
        Method m = new Method(name, ret, params, jni, cabi, strategy, engineName, ifaceMethod);

        if (cabi != null) {
            for (Kind k : params) {
                if (k.isArray()) {
                    error(ee, "@Cabi method cannot take arrays (FFM is pointer-based): " + m.name);
                    break;
                }
            }
            if (ret.isArray()) error(ee, "@Cabi method cannot return an array: " + m.name);
        }
        return m;
    }

    /**
     * Classify a type + @Ptr flag into a {@link Kind}, reporting misuse.
     */
    private Kind classify(Element where, TypeMirror t, boolean ptr, boolean isReturn) {
        boolean segment = t.toString().equals(SEGMENT);
        if (ptr) {
            if (!segment) {
                error(where, "@Ptr only applies to MemorySegment");
                return Kind.PTR;
            }
            return Kind.PTR;
        }
        if (segment) {
            error(where, "MemorySegment parameter/return must be annotated @Ptr");
            return Kind.PTR;
        }
        return switch (t.getKind()) {
            case VOID -> Kind.VOID;
            case LONG -> Kind.LONG;
            case INT -> Kind.INT;
            case BYTE -> Kind.BYTE;
            case ARRAY -> {
                TypeMirror c = ((ArrayType) t).getComponentType();
                yield switch (c.getKind()) {
                    case BYTE -> Kind.BYTE_ARRAY;
                    case LONG -> Kind.LONG_ARRAY;
                    default -> {
                        error(where, "unsupported array type: " + t);
                        yield Kind.LONG_ARRAY;
                    }
                };
            }
            default -> {
                error(where, "unsupported type: " + t);
                yield Kind.LONG;
            }
        };
    }

    private void error(Element e, String msg) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, e);
    }

    private enum Kind {
        VOID, PTR, LONG, INT, BYTE, BYTE_ARRAY, LONG_ARRAY;

        boolean isArray() {
            return this == BYTE_ARRAY || this == LONG_ARRAY;
        }

        String loweredJava() {
            return switch (this) {
                case VOID -> "void";
                case PTR, LONG -> "long";
                case INT -> "int";
                case BYTE -> "byte";
                case BYTE_ARRAY -> "byte[]";
                case LONG_ARRAY -> "long[]";
            };
        }

        String jniSig() {
            return switch (this) {
                case VOID -> "V";
                case PTR, LONG -> "J";
                case INT -> "I";
                case BYTE -> "B";
                case BYTE_ARRAY -> "[B";
                case LONG_ARRAY -> "[J";
            };
        }

        String segmentJava() {
            return switch (this) {
                case VOID -> "void";
                case PTR -> "MemorySegment";
                case LONG -> "long";
                case INT -> "int";
                case BYTE -> "byte";
                case BYTE_ARRAY -> "byte[]";
                case LONG_ARRAY -> "long[]";
            };
        }

        String layout() {
            return switch (this) {
                case PTR -> "ADDRESS";
                case LONG -> "JAVA_LONG";
                case INT -> "JAVA_INT";
                case BYTE -> "JAVA_BYTE";
                default -> throw new IllegalStateException("no layout for " + this);
            };
        }
    }

    /**
     * @param jni        nullable
     * @param cabi       nullable
     * @param strategy   nullable: binding-only
     * @param engineName engine method name (defaults to {@link #name})
     * @param ifaceMethod whether the engine method overrides the public interface
     */
    private record Method(String name, Kind ret, List<Kind> params, Jni jni, Cabi cabi, Marshal.Strategy strategy,
                          String engineName, boolean ifaceMethod) {

        String loweredReturn() {
            return ret.loweredJava();
        }

        String segmentReturn() {
            return ret.segmentJava();
        }

        String loweredParams() {
            return params(Kind::loweredJava);
        }

        String segmentParams() {
            return params(Kind::segmentJava);
        }

        private String params(Function<Kind, String> ty) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(ty.apply(params.get(i))).append(" p").append(i);
            }
            return sb.toString();
        }

        /**
         * Args passed from the segment-level wrapper down to the lowered holder.
         */
        String loweredArgs() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(params.get(i) == Kind.PTR ? "p" + i + ".address()" : "p" + i);
            }
            return sb.toString();
        }

        /**
         * Engine-level params: like {@link #segmentParams()} but {@code @NotNull} on pointers.
         */
        String engineParams() {
            return engineParams(0);
        }

        /**
         * Session-method engine params: pointers get {@code @NotNull}, skipping the state handle.
         */
        String sessionEngineParams() {
            return engineParams(1);
        }

        private String engineParams(int from) {
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < params.size(); i++) {
                if (i > from) sb.append(", ");
                Kind k = params.get(i);
                if (k == Kind.PTR) sb.append("@NotNull MemorySegment p").append(i);
                else sb.append(k.segmentJava()).append(" p").append(i);
            }
            return sb.toString();
        }

        /**
         * Public session-method params: every param after the leading state handle.
         */
        String sessionParams() {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < params.size(); i++) {
                if (i > 1) sb.append(", ");
                sb.append(params.get(i).segmentJava()).append(" p").append(i);
            }
            return sb.toString();
        }

        /**
         * Args to the binding from a session method: the {@code state} field then the rest.
         */
        String sessionArgs() {
            StringBuilder sb = new StringBuilder("state");
            for (int i = 1; i < params.size(); i++) sb.append(", p").append(i);
            return sb.toString();
        }

        /**
         * Args passed straight through to invokeExact (segment-level).
         */
        String segmentArgs() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("p").append(i);
            }
            return sb.toString();
        }

        String jniSig() {
            StringBuilder sb = new StringBuilder("(");
            for (Kind k : params) sb.append(k.jniSig());
            return sb.append(")").append(ret.jniSig()).toString();
        }

        String functionDescriptor() {
            String params = this.params.stream().map(Kind::layout).collect(Collectors.joining(", "));
            if (ret == Kind.VOID) {
                return "FunctionDescriptor.ofVoid(" + params + ")";
            }
            String lead = ret.layout();
            return "FunctionDescriptor.of(" + lead + (params.isEmpty() ? "" : ", " + params) + ")";
        }
    }
}
