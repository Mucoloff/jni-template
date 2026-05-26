package dev.sweety.example.buffer;

import dev.sweety.Backend;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Runs the buffer example end-to-end over off-heap {@link MemorySegment}s, through both
 * the generated FFM and JNI bindings, against this example's own native lib.
 */
public class Main {
    public static void main(String[] args) {
        FfmBindings ffm = new FfmBindings(Backend.CPP);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(8);
            ffm.fill(buf, 8, (byte) 3);
            System.out.println("FFM sum(fill 3 x8) = " + ffm.sum(buf, 8));   // 24
            MemorySegment dst = a.allocate(8);
            ffm.copy(dst, buf, 8);
            System.out.println("FFM sum(copy)      = " + ffm.sum(dst, 8));   // 24
        }

        JniBindings jni = JniBindings.of(Backend.CPP);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(4);
            jni.fill(buf, 4, (byte) 5);
            System.out.println("JNI sum(fill 5 x4) = " + jni.sum(buf, 4));   // 20
        }
    }
}
