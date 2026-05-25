package dev.sweety;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class Main {
    void main() {
        NativeLib.ensureLoaded();
        System.out.println("Backend: " + NativeLib.current());

        // Scalar — static JNI calls
        System.out.println("sum(5, 10)      = " + Scalar.sum(5, 10));
        System.out.println("subtract(10, 3) = " + Scalar.subtract(10, 3));

        // Buffer — native malloc / direct ByteBuffer
        ByteBuffer buf = Buffer.allocate(4);
        buf.order(ByteOrder.nativeOrder()).putInt(0, 0x000000FF);
        System.out.println("before process: 0x" + Integer.toHexString(buf.getInt(0)));
        Buffer.process(buf, 4);
        System.out.println("after  process: 0x" + Integer.toHexString(buf.getInt(0)));
        Buffer.free(buf);

        // Engine — stateful handle, implements Calculator interface (Kotlin)
        try (Engine e = new Engine()) {
            System.out.println("engine.sum(2,3)      = " + e.sum(2, 3));
            System.out.println("engine.subtract(9,4) = " + e.subtract(9, 4));
        }
    }
}
