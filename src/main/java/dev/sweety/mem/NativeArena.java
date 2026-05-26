package dev.sweety.mem;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Small helpers for moving {@code byte[]} into off-heap {@link MemorySegment}s,
 * which both the JNI (via {@code segment.address()}) and FFM bindings hash
 * without copying. For repeated use prefer {@link SegmentPool}.
 */
public final class NativeArena {

    private NativeArena() {
    }

    /**
     * Copy {@code data} into a freshly allocated native segment bound to {@code arena}.
     */
    public static MemorySegment copyOf(Arena arena, byte[] data) {
        MemorySegment seg = arena.allocate(data.length);
        fill(seg, data);
        return seg;
    }

    /**
     * Copy {@code data} into the front of an existing native segment; returns the byte length.
     */
    public static long fill(MemorySegment seg, byte[] data) {
        MemorySegment.copy(data, 0, seg, ValueLayout.JAVA_BYTE, 0, data.length);
        return data.length;
    }
}
