package dev.sweety.bench;

import dev.sweety.Backend;
import dev.sweety.Binding;
import dev.sweety.HashEngine;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/** Shared parameterisation: pick a binding (JNI/FFM) and backend (CPP/RUST). */
@State(Scope.Thread)
public class EngineState {
    @Param({"JNI", "FFM"})
    public Binding binding;

    @Param({"CPP", "RUST"})
    public Backend backend;

    public HashEngine engine;

    public void initEngine() {
        engine = HashEngine.of(binding, backend);
    }
}
