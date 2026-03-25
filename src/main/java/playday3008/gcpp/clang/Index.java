package playday3008.gcpp.clang;

import java.lang.foreign.MemorySegment;

/**
 * The Index type provides the primary interface to the Clang CIndex library,
 * primarily by providing an interface for reading and parsing translation units.
 * <p>
 * Wraps an opaque {@code CXIndex} pointer. Implements {@link AutoCloseable}
 * for deterministic resource cleanup.
 */
public final class Index implements AutoCloseable {
    private MemorySegment pointer;

    private Index(MemorySegment pointer) {
        this.pointer = pointer;
    }

    /**
     * Create a new Index.
     *
     * @param excludeDeclarationsFromPCH exclude local declarations from translation units
     * @return the newly created index
     */
    public static Index create(boolean excludeDeclarationsFromPCH) {
        return new Index(LibClang.createIndex(excludeDeclarationsFromPCH ? 1 : 0, 0));
    }

    /**
     * Create a new Index with default options.
     *
     * @return the newly created index
     */
    public static Index create() {
        return create(false);
    }

    MemorySegment getPointer() {
        return pointer;
    }

    @Override
    public void close() {
        if (pointer != null && !pointer.equals(MemorySegment.NULL)) {
            LibClang.disposeIndex(pointer);
            pointer = null;
        }
    }
}
