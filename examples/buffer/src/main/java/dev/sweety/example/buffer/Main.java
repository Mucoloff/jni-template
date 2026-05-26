package dev.sweety.example.buffer;

import dev.sweety.Backend;
import dev.sweety.Binding;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Runs the buffer example end-to-end over off-heap {@link MemorySegment}s across every
 * backend (C++ and Rust), through both the generated FFM and JNI bindings.
 */
public class Main {
    public static void main(String[] args) {
        for (Backend b : Backend.values()) {
            try {
                Bindings ffm = Bindings.of(Binding.FFM, b);
                try (Arena a = Arena.ofConfined()) {
                    MemorySegment buf = a.allocate(8);
                    ffm.fill(buf, 8, (byte) 3);
                    MemorySegment dst = a.allocate(8);
                    ffm.copy(dst, buf, 8);
                    System.out.printf("FFM %-4s sum(fill 3x8)=%d sum(copy)=%d%n", b, ffm.sum(buf, 8), ffm.sum(dst, 8));
                }
                Bindings jni = Bindings.of(Binding.JNI, b);
                try (Arena a = Arena.ofConfined()) {
                    MemorySegment buf = a.allocate(4);
                    jni.fill(buf, 4, (byte) 5);
                    System.out.printf("JNI %-4s sum(fill 5x4)=%d%n", b, jni.sum(buf, 4));
                }
            } catch (Throwable t) {
                System.out.println(b + " unavailable: " + t);
            }
        }
    }
}
