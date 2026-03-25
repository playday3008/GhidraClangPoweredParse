package playday3008.gcpp.clang;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Represents a particular source location within a translation unit.
 * <p>
 * Wraps a {@code CXSourceLocation} struct (passed by value as a MemorySegment).
 */
public final class SourceLocation {

    private final MemorySegment segment;
    private final Arena arena;

    /**
     * A file location with filename, line, column, and byte offset.
     */
    public record FileLocation(String filename, int line, int column, int offset) {}

    SourceLocation(MemorySegment segment, Arena arena) {
        this.segment = segment;
        this.arena = arena;
    }

    /**
     * Returns whether this source location is in a system header.
     */
    public boolean isInSystemHeader() {
        return LibClang.isInSystemHeader(segment) != 0;
    }

    /**
     * Decompose this source location into file, line, column, and offset components.
     *
     * @return a {@link FileLocation} record with the decomposed location
     */
    public FileLocation getFileLocation() {
        // Allocate output parameters
        MemorySegment fileSeg = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment lineSeg = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment columnSeg = arena.allocate(ValueLayout.JAVA_INT);
        MemorySegment offsetSeg = arena.allocate(ValueLayout.JAVA_INT);

        LibClang.getFileLocation(segment, fileSeg, lineSeg, columnSeg, offsetSeg);

        int line = lineSeg.get(ValueLayout.JAVA_INT, 0);
        int column = columnSeg.get(ValueLayout.JAVA_INT, 0);
        int offset = offsetSeg.get(ValueLayout.JAVA_INT, 0);

        // Extract filename from the CXFile pointer
        MemorySegment filePtr = fileSeg.get(ValueLayout.ADDRESS, 0);
        String filename = "";
        if (filePtr != null && !filePtr.equals(MemorySegment.NULL)) {
            MemorySegment cxStr = LibClang.getFileName(arena, filePtr);
            filename = LibClang.extractString(arena, cxStr);
        }

        return new FileLocation(filename, line, column, offset);
    }

    @Override
    public String toString() {
        FileLocation loc = getFileLocation();
        return loc.filename() + ":" + loc.line() + ":" + loc.column();
    }
}
