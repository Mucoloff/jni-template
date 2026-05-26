package dev.sweety.example.mathops;

import dev.sweety.Backend;

/**
 * Runs the mathops example end-to-end: the generated FFM and JNI bindings call the C++
 * native lib built for THIS example (libnative_cpp.so under its own build/natives). Proof
 * that the framework's generated code works for a spec unrelated to the FNV demo.
 */
public class Main {
    public static void main(String[] args) {
        FfmBindings ffm = new FfmBindings(Backend.CPP);
        System.out.println("FFM add(2,3)   = " + ffm.add(2, 3));
        System.out.println("FFM imul(4,5)  = " + ffm.imul(4, 5));
        System.out.println("FFM neg(7)     = " + ffm.neg((byte) 7));

        JniBindings jni = JniBindings.of(Backend.CPP);
        System.out.println("JNI add(2,3)   = " + jni.add(2, 3));
        System.out.println("JNI imul(4,5)  = " + jni.imul(4, 5));
        System.out.println("JNI neg(7)     = " + jni.neg((byte) 7));
    }
}
