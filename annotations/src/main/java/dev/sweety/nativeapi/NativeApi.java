package dev.sweety.nativeapi;

import java.lang.annotation.*;

/**
 * Marks an interface as the single source of truth for a JNI native surface.
 * The annotation processor generates one {@code native}-method holder class per
 * backend (e.g. {@code CppNatives}, {@code RustNatives}) plus a JSON descriptor
 * that the C++ and Rust builds turn into their RegisterNatives tables.
 *
 * <p>{@link #backendNames()} and {@link #backendEnums()} are parallel arrays: the
 * i-th backend generates {@code <name>Natives} loading {@code Backend.<enum>}.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface NativeApi {
    /** Holder class name prefixes, e.g. {"Cpp","Rust"} -> CppNatives, RustNatives. */
    String[] backendNames() default {"Cpp", "Rust"};

    /** dev.sweety.Backend enum constants, parallel to {@link #backendNames()}. */
    String[] backendEnums() default {"CPP", "RUST"};
}
