package dev.sweety;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every available Binding x Backend must agree on FNV-1a, across all API paths.
 */
class ParityTest {

    private static final byte[] MSG = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
    private static final long EXPECTED = 0x59aeb7b40bd8c122L; // golden FNV-1a value

    @Test
    void allBindingsAndBackendsAgree() {
        List<String> ran = new ArrayList<>();
        for (Binding binding : Binding.values()) {
            for (Backend backend : Backend.getEntries()) {

                try {
                    HashEngine e = HashEngine.of(binding, backend);
                    assertAllPathsEqual(e);
                } catch (UnsatisfiedLinkError | IllegalStateException e) {
                    System.err.println("skipping " + binding + "/" + backend + ": " + e.getMessage());
                    continue; // backend library absent (e.g. Rust without cargo)
                }
                ran.add(binding + "/" + backend);
            }
        }
        System.out.println("ran combos: " + ran);
        assertTrue(ran.contains("JNI/CPP"), "JNI/CPP must always be available");
    }

    private void assertAllPathsEqual(HashEngine e) throws UnsatisfiedLinkError, IllegalStateException {
        String tag = e.binding() + "/" + e.backend();
        assertEquals(EXPECTED, e.hash(MSG), tag + " array");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(MSG.length);
            MemorySegment.copy(MSG, 0, seg, ValueLayout.JAVA_BYTE, 0, MSG.length);

            assertEquals(EXPECTED, e.hash(seg, MSG.length), tag + " segment");
            assertEquals(EXPECTED, e.hashBatch(new MemorySegment[]{seg}, new long[]{MSG.length})[0], tag + " batch");

            try (HashSession s = e.open()) {
                s.update(seg, MSG.length);
                assertEquals(EXPECTED, s.digest(), tag + " stream");
                s.reset();
                assertEquals(0xcbf29ce484222325L, s.digest(), tag + " reset");
            }
        }
    }
}
