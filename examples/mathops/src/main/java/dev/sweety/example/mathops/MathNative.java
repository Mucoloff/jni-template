package dev.sweety.example.mathops;

import dev.sweety.nativeapi.Cabi;
import dev.sweety.nativeapi.Jni;
import dev.sweety.nativeapi.NativeApi;

/**
 * Example native surface of pure primitives — no pointers, no engine, no FNV. The same
 * framework processor that handles the FNV hash spec generates the JNI holders/bindings
 * and the FFM downcall bindings for this completely different API, with no processor
 * changes: that is what makes it a framework rather than a template.
 */
@NativeApi
interface MathNative {

    @Jni(thunk = "jni_add")
    @Cabi("nat_add")
    long add(long a, long b);

    @Jni(thunk = "jni_imul")
    @Cabi("nat_imul")
    int imul(int a, int b);

    @Jni(thunk = "jni_neg")
    @Cabi("nat_neg")
    byte neg(byte x);
}
