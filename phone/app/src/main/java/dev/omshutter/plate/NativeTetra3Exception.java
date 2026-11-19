package dev.omshutter.plate;

/** A checked native-input, index, lens, lifecycle, or solver failure. */
public final class NativeTetra3Exception extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NativeTetra3Exception(String message) {
        super(message);
    }
}
