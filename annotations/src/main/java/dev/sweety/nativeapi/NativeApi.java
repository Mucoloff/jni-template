package dev.sweety.nativeapi;

import java.lang.annotation.*;

/**
 * Marks an interface as the single source of truth for a JNI native surface.
 * The annotation processor generates one {@code native}-method holder class per
 * backend (e.g. {@code CppNatives}, {@code RustNatives}) plus a JSON descriptor
 * that the C++ and Rust builds turn into their RegisterNatives tables.
 *
 * <p>{@link #backendEnums()} the
 * i-th backend generates {@code <name>Natives} loading {@code Backend.<enum>}.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface NativeApi {
    /** dev.sweety.Backend enum constants. */
    String[] backendEnums() default {"CPP", "RUST"};
    /** Holder class name prefixes, e.g. {"Cpp","Rust"} -> CppNatives, RustNatives. */

    /** Native core type the generated C-ABI {@link Core} bodies delegate to (C++ struct / Rust struct). */
    String coreType() default "Fnv";
}
