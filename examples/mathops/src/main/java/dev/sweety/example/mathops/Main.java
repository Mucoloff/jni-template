package dev.sweety.example.mathops;

import dev.sweety.Backend;

/**
 * Runs the mathops example end-to-end across every backend (C++ and Rust), through both
 * the generated FFM and JNI bindings calling this example's own native libs. Proof that
 * the framework's generated code works for a spec unrelated to the FNV demo.
 */
public class Main {
    public static void main(String[] args) {
        for (Backend b : Backend.getEntries()) {
            try {
                FfmBindings ffm = new FfmBindings(b);
                System.out.printf("FFM %-4s add(2,3)=%d imul(4,5)=%d neg(7)=%d%n",
                        b, ffm.add(2, 3), ffm.imul(4, 5), ffm.neg((byte) 7));
                JniBindings jni = JniBindings.of(b);
                System.out.printf("JNI %-4s add(2,3)=%d imul(4,5)=%d neg(7)=%d%n",
                        b, jni.add(2, 3), jni.imul(4, 5), jni.neg((byte) 7));
            } catch (Throwable t) {
                System.out.println(b + " unavailable: " + t);
            }
        }
    }
}
