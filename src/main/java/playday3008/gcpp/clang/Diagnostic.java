package playday3008.gcpp.clang;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * A Diagnostic is a single instance of a Clang diagnostic. It includes the
 * diagnostic severity, the message, the location the diagnostic occurred, as
 * well as additional source ranges and associated fix-it hints.
 * <p>
 * Wraps an opaque {@code CXDiagnostic} pointer.
 */
public final class Diagnostic implements AutoCloseable {

    /** CXDiagnosticSeverity constants */
    public static final int SEVERITY_IGNORED = 0;
    public static final int SEVERITY_NOTE = 1;
    public static final int SEVERITY_WARNING = 2;
    public static final int SEVERITY_ERROR = 3;
    public static final int SEVERITY_FATAL = 4;

    private MemorySegment pointer;
    private final Arena arena;

    Diagnostic(MemorySegment pointer, Arena arena) {
        this.pointer = pointer;
        this.arena = arena;
    }

    /**
     * Returns the severity of this diagnostic.
     *
     * @return one of the SEVERITY_* constants
     */
    public int severity() {
        return LibClang.getDiagnosticSeverity(pointer);
    }

    /**
     * Format this diagnostic into a human-readable string using the given options.
     *
     * @param options diagnostic display option flags
     * @return the formatted diagnostic string
     */
    public String format(int options) {
        MemorySegment cxStr = LibClang.formatDiagnostic(arena, pointer, options);
        return LibClang.extractString(arena, cxStr);
    }

    /**
     * Format this diagnostic into a human-readable string using default display options.
     *
     * @return the formatted diagnostic string
     */
    public String format() {
        return format(LibClang.defaultDiagnosticDisplayOptions());
    }

    @Override
    public void close() {
        if (pointer != null && !pointer.equals(MemorySegment.NULL)) {
            LibClang.disposeDiagnostic(pointer);
            pointer = null;
        }
    }

    @Override
    public String toString() {
        return format();
    }
}
