package dev.sweety;

/**
 * Stateless JNI pattern: static native methods, no native state to manage.
 *
 * <p>The {@code byte[]} crosses the boundary via {@code GetByteArrayElements},
 * which may copy the array into native memory. Simple to use; pay a copy on
 * large inputs. For zero-copy on hot paths, see {@link NativeBuffer}.
 */
public final class Checksum {
    static { NativeLib.ensureLoaded(); }

    private Checksum() {}

    /** One-shot FNV-1a 64-bit hash of {@code data}. */
    public static native long hash(byte[] data);
}
