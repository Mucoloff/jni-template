package dev.sweety;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

class Main {

    void main() {
        NativeLib.ensureLoaded();
        System.out.println("Backend: " + NativeLib.current());

        byte[] msg = "the quick brown fox".getBytes(StandardCharsets.UTF_8);

        // 1. Checksum — stateless static call, byte[] copied across the boundary.
        long oneShot = Checksum.hash(msg);
        System.out.printf("Checksum.hash         = 0x%016x%n", oneShot);

        // 2. NativeBuffer — zero-copy hash over off-heap memory.
        ByteBuffer buf = NativeBuffer.allocate(msg.length);
        buf.put(msg).flip();
        long direct = NativeBuffer.hash(buf, msg.length);
        System.out.printf("NativeBuffer.hash     = 0x%016x%n", direct);
        NativeBuffer.free(buf);

        // 3. Hasher — stateful handle, incremental digest in two chunks.
        long streamed;
        try (Hasher h = new Hasher()) {
            h.update(java.util.Arrays.copyOfRange(msg, 0, 9));   // "the quick"
            h.update(java.util.Arrays.copyOfRange(msg, 9, msg.length));
            streamed = h.digest();
        }
        System.out.printf("Hasher (streamed)     = 0x%016x%n", streamed);

        boolean consistent = oneShot == direct && direct == streamed;
        System.out.println("all three agree       = " + consistent);
        if (!consistent) throw new AssertionError("FNV-1a paths disagree");

        benchmark();
    }

    /**
     * Throughput of the zero-copy path; run with -Djni.backend=rust to compare.
     */
    private static void benchmark() {
        final int size = 64 << 20; // 64 MiB
        final int iters = 20;
        ByteBuffer buf = NativeBuffer.allocate(size);
        // Touch every page with non-zero data so the loop does real work.
        for (int i = 0; i < size; i += 4096) buf.put(i, (byte) i);

        for (int i = 0; i < 5; i++) NativeBuffer.hash(buf, size); // warmup

        long acc = 0, start = System.nanoTime();
        for (int i = 0; i < iters; i++) acc ^= NativeBuffer.hash(buf, size);
        long ns = System.nanoTime() - start;

        double mbPerSec = (double) size * iters / (ns / 1e9) / (1 << 20);
        System.out.printf("%nbenchmark: %d MiB x %d in %.1f ms -> %.0f MiB/s (sink=0x%x)%n",
                size >> 20, iters, ns / 1e6, mbPerSec, acc);
        NativeBuffer.free(buf);
    }
}
