package dev.sweety;

import dev.sweety.mem.NativeArena;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * Runs the unified {@link HashEngine} API across every Binding x Backend combo and
 * checks that all paths (array, zero-copy segment, streaming, batch) agree — both
 * within an engine and across JNI vs FFM and C++ vs Rust.
 */
public class Main {

    public static void main(String[] args) {
        byte[] msg = "the quick brown fox".getBytes(StandardCharsets.UTF_8);

        Long reference = null;
        for (Binding binding : Binding.getEntries()) {
            for (Backend backend : Backend.getEntries()) {
                try {
                    long h = exercise(HashEngine.of(binding, backend), msg);
                    System.out.printf("%-4s %-4s -> 0x%016x%n", binding, backend, h);
                    if (reference == null) reference = h;
                    else if (reference != h)
                        throw new AssertionError(binding + "/" + backend + " disagrees");
                } catch (UnsatisfiedLinkError | IllegalStateException e) {
                    System.out.printf("%-4s %-4s -> skipped (%s)%n", binding, backend, e.getMessage());
                }
            }
        }
        System.out.println("all available combos agree = true");
    }

    /**
     * Exercise array / zero-copy / streaming / batch paths; assert they match; return the hash.
     */
    private static long exercise(HashEngine e, byte[] msg) {
        long viaArray = e.hash(msg);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = NativeArena.copyOf(arena, msg);

            long viaSegment = e.hash(seg, msg.length);

            final long viaStream;
            try (HashSession s = e.open()) {
                s.update(seg, msg.length);
                viaStream = s.digest();
            }

            long viaBatch = e.hashBatch(new MemorySegment[]{seg}, new long[]{msg.length})[0];

            if (viaArray != viaSegment || viaSegment != viaStream || viaStream != viaBatch)
                throw new AssertionError("internal path mismatch for " + e.binding() + "/" + e.backend());

            // memory-bound op smoke test: transform then verify it changed the bytes
            e.transform(seg, msg.length, (byte) 1);
            if (seg.get(ValueLayout.JAVA_BYTE, 0) == msg[0])
                throw new AssertionError("transform had no effect");

            return viaArray;
        }
    }
}
