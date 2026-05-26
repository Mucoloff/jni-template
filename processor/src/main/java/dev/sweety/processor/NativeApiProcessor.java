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

        writeRawNatives(pkg, jni);
        for (int i = 0; i < names.length; i++) writeHolder(pkg, names[i] + "Natives", enums[i], jni);
        writeJniBindings(pkg, names, enums, jni);
        writeFfmBindings(pkg, ffm);
        writeDescriptor(names, holders, jni);

        Engine engine = iface.getAnnotation(Engine.class);
        if (engine != null) {
            // Bases generated in the binding package; subclasses (hand-written) extend them.
            writeEngineBase(pkg, engine, "Jni", "JNI", "JniBindings", "JniBindings.of(backend)",
                    methods.stream().filter(x -> x.jni != null).collect(Collectors.toList()));
            writeEngineBase(pkg, engine, "Ffm", "FFM", "FfmBindings", "new FfmBindings(backend)",
                    methods.stream().filter(x -> x.cabi != null).collect(Collectors.toList()));
        }
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
            public final class JniBindings {
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
            public final class FfmBindings {
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

    private static final String ENGINE_BASE = """
            package %pkg%;
            
            import dev.sweety.Backend;
            import dev.sweety.Binding;
            import %iface%;
            import %session%;
            import dev.sweety.pool.ObjectPool;
            import org.jetbrains.annotations.NotNull;
            import java.lang.foreign.MemorySegment;
            
            // Generated by NativeApiProcessor. Do not edit. DIRECT + SESSION half of the %ifaceName% impl.
            public abstract class %base% implements %ifaceName% {
                protected final Backend backend;
                protected final %binding% bindings;
                protected final ObjectPool<%sessionCls%> sessions;
            
                protected %base%(Backend backend) {
                    this.backend = backend;
                    this.bindings = %construct%;
                    this.sessions = ObjectPool.threadLocal(
                            () -> new %sessionCls%(bindings.%create%()),
                            s -> bindings.%reset%(s.state),
                            s -> bindings.%free%(s.state),
                            %poolSize%);
                }
            
            %direct%
            
                @Override public @NotNull %sessionName% open() { return sessions.acquire(); }
                @Override public @NotNull Backend backend() { return backend; }
                @Override public @NotNull Binding binding() { return Binding.%bindingEnum%; }
            
                final class %sessionCls% implements %sessionName% {
                    final MemorySegment state;
                    %sessionCls%(MemorySegment state) { this.state = state; }
            %sessionMethods%
                    @Override public void close() { sessions.release(this); }
                }
            }
            """;

    /**
     * One {@code abstract} engine base per binding: DIRECT delegates + pooled SESSION.
     */
    private void writeEngineBase(String pkg, Engine engine, String prefix, String bindingEnum,
                                 String binding, String construct, List<Method> bound) throws IOException {
        String iface = engine.iface();
        String session = engine.session();
        String base = prefix + engine.baseSuffix();
        String sessionCls = prefix + "Session";

        Map<Marshal.Strategy, Method> roles = new EnumMap<>(Marshal.Strategy.class);
        List<Method> direct = new ArrayList<>();
        for (Method m : bound) {
            if (m.strategy == null) continue;
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
            if (sm.length() > 0) sm.append("\n");
            sm.append("        @Override public ").append(m.segmentReturn()).append(" ").append(m.name)
                    .append("(").append(m.sessionEngineParams()).append(") {\n")
                    .append("            ").append(m.ret == Kind.VOID ? "" : "return ")
                    .append("bindings.").append(m.name).append("(").append(m.sessionArgs()).append(");\n")
                    .append("        }");
        }

        write(pkg + "." + base, ENGINE_BASE
                .replace("%pkg%", pkg)
                .replace("%iface%", iface)
                .replace("%session%", session)
                .replace("%ifaceName%", simpleName(iface))
                .replace("%sessionName%", simpleName(session))
                .replace("%base%", base)
                .replace("%binding%", binding)
                .replace("%construct%", construct)
                .replace("%sessionCls%", sessionCls)
                .replace("%create%", create.name)
                .replace("%reset%", reset.name)
                .replace("%free%", free.name)
                .replace("%poolSize%", Integer.toString(engine.poolSize()))
                .replace("%bindingEnum%", bindingEnum)
                .replace("%direct%", directMethods)
                .replace("%sessionMethods%", sm.toString()));
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
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

    private void writeJniBindings(String pkg, String[] names, String[] enums, List<Method> jni) throws IOException {
        StringBuilder cases = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) cases.append("\n");
            cases.append("            case ").append(enums[i]).append(" -> ").append(names[i]).append("Natives.get();");
        }
        write(pkg + ".JniBindings", JNI_BINDINGS
                .replace("%pkg%", pkg)
                .replace("%cases%", cases.toString())
                .replace("%wrappers%", join(jni, m ->
                        "    public " + m.segmentReturn() + " " + m.name + "(" + m.segmentParams() + ") {\n"
                                + "        " + jniBody(m) + "\n"
                                + "    }")));
    }

    private void writeFfmBindings(String pkg, List<Method> ffm) throws IOException {
        write(pkg + ".FfmBindings", FFM_BINDINGS
                .replace("%pkg%", pkg)
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
        Method m = new Method(ee.getSimpleName().toString(), ret, params, jni, cabi, strategy);

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
     * @param jni      nullable
     * @param cabi     nullable
     * @param strategy nullable: binding-only
     */
    private record Method(String name, Kind ret, List<Kind> params, Jni jni, Cabi cabi, Marshal.Strategy strategy) {
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
