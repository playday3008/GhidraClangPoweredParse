package playday3008.gcpp.clang;

import playday3008.gcpp.clang.error.ParseException;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Represents a source code translation unit.
 * <p>
 * This is one of the main types in the API. Any time you wish to interact
 * with Clang's representation of a source file, you typically start with a
 * translation unit.
 * <p>
 * Owns a shared {@link Arena} used to allocate return-by-value struct segments
 * for {@link Cursor} and {@link Type} objects produced by this TU.
 */
public final class TranslationUnit implements AutoCloseable {

    /** Parse flag: Indicates that the translation unit is incomplete (e.g. headers). */
    public static final int INCOMPLETE = 0x02;

    /** Parse flag: Do not parse function bodies. */
    public static final int SKIP_FUNCTION_BODIES = 0x40;

    /** Parse flag: Continue parsing even after fatal errors. */
    public static final int KEEP_GOING = 0x200;

    /** Parse flag: Include attributed types in the CXType results. */
    public static final int INCLUDE_ATTRIBUTED_TYPES = 0x1000;

    /** Parse flag: Visit implicit attributes attached to declarations. */
    public static final int VISIT_IMPLICIT_ATTRIBUTES = 0x2000;

    private MemorySegment pointer;
    private final Arena arena;

    // Hold a reference to prevent GC/close of Index while this TU is alive
    @SuppressWarnings("unused")
    private final Index index;

    private TranslationUnit(MemorySegment pointer, Arena arena, Index index) {
        this.pointer = pointer;
        this.arena = arena;
        this.index = index;
    }

    /**
     * Returns the cursor representing the root of the translation unit's AST.
     */
    public Cursor cursor() {
        MemorySegment cursorSeg = LibClang.getTranslationUnitCursor(arena, pointer);
        return new Cursor(cursorSeg, arena);
    }

    /**
     * Returns the number of diagnostics produced during parsing.
     */
    public int getNumDiagnostics() {
        return LibClang.getNumDiagnostics(pointer);
    }

    /**
     * Returns the diagnostic at the given index.
     *
     * @param index the zero-based diagnostic index
     * @return the diagnostic (caller must close when done)
     */
    public Diagnostic getDiagnostic(int index) {
        MemorySegment diagPtr = LibClang.getDiagnostic(pointer, index);
        return new Diagnostic(diagPtr, arena);
    }

    /**
     * Returns the shared Arena used by this translation unit for allocating
     * struct segments (CXCursor, CXType, CXSourceLocation, CXString, etc.).
     */
    public Arena getArena() {
        return arena;
    }

    MemorySegment getPointer() {
        return pointer;
    }

    @Override
    public void close() {
        if (pointer != null && !pointer.equals(MemorySegment.NULL)) {
            LibClang.disposeTranslationUnit(pointer);
            pointer = null;
        }
        arena.close();
    }

    /**
     * Builder for creating TranslationUnit instances with a fluent API.
     */
    public static class Builder {
        private final String sourceFilename;
        private String[] commandLineArgs;
        private String unsavedFileName;
        private String unsavedFileContents;
        private int options;

        public Builder(String sourceFilename) {
            this.sourceFilename = sourceFilename;
            this.options = 0;
        }

        /**
         * Set command-line arguments passed to clang (e.g. "-Wall", "-I/path/to/include").
         */
        public Builder args(String... args) {
            this.commandLineArgs = args;
            return this;
        }

        /**
         * Provide an unsaved file (virtual file contents in memory).
         */
        public Builder unsavedFile(String name, String contents) {
            this.unsavedFileName = name;
            this.unsavedFileContents = contents;
            return this;
        }

        /**
         * Indicates that the translation unit is incomplete (typically used for headers).
         */
        public Builder parseIncomplete() {
            this.options |= INCOMPLETE;
            return this;
        }

        /**
         * Do not parse function bodies.
         */
        public Builder skipFunctionBodies() {
            this.options |= SKIP_FUNCTION_BODIES;
            return this;
        }

        /**
         * Continue parsing even after fatal errors.
         */
        public Builder keepGoing() {
            this.options |= KEEP_GOING;
            return this;
        }

        /**
         * Include attributed types in the CXType results.
         */
        public Builder includeAttributedTypes() {
            this.options |= INCLUDE_ATTRIBUTED_TYPES;
            return this;
        }

        /**
         * Visit implicit attributes attached to declarations.
         */
        public Builder visitImplicitAttributes() {
            this.options |= VISIT_IMPLICIT_ATTRIBUTES;
            return this;
        }

        /**
         * Build and parse the translation unit.
         *
         * @param index the Index to parse within
         * @return the parsed TranslationUnit
         * @throws ParseException if parsing fails
         */
        public TranslationUnit build(Index index) throws ParseException {
            Arena arena = Arena.ofShared();
            try {
                // Allocate source filename as native string
                MemorySegment filenameSeg = sourceFilename != null
                    ? arena.allocateUtf8String(sourceFilename)
                    : MemorySegment.NULL;

                // Allocate command line args as array of pointers to strings
                MemorySegment argsSeg = MemorySegment.NULL;
                int numArgs = 0;
                if (commandLineArgs != null && commandLineArgs.length > 0) {
                    numArgs = commandLineArgs.length;
                    argsSeg = arena.allocateArray(ValueLayout.ADDRESS, numArgs);
                    for (int i = 0; i < numArgs; i++) {
                        MemorySegment argStr = arena.allocateUtf8String(commandLineArgs[i]);
                        argsSeg.setAtIndex(ValueLayout.ADDRESS, i, argStr);
                    }
                }

                // Allocate unsaved files
                MemorySegment unsavedSeg = MemorySegment.NULL;
                int numUnsaved = 0;
                if (unsavedFileName != null && unsavedFileContents != null) {
                    numUnsaved = 1;
                    unsavedSeg = arena.allocate(LibClang.CX_UNSAVED_FILE);
                    MemorySegment nameStr = arena.allocateUtf8String(unsavedFileName);
                    MemorySegment contentsStr = arena.allocateUtf8String(unsavedFileContents);
                    unsavedSeg.set(ValueLayout.ADDRESS, 0, nameStr);
                    unsavedSeg.set(ValueLayout.ADDRESS, 8, contentsStr);
                    unsavedSeg.set(ValueLayout.JAVA_LONG, 16, (long) unsavedFileContents.length());
                }

                // Allocate output pointer for the translation unit
                MemorySegment outTU = arena.allocate(ValueLayout.ADDRESS);

                int result = LibClang.parseTranslationUnit2(
                    index.getPointer(), filenameSeg, argsSeg, numArgs,
                    unsavedSeg, numUnsaved, options, outTU);

                if (result != 0) {
                    arena.close();
                    throw new ParseException(result);
                }

                MemorySegment tuPointer = outTU.get(ValueLayout.ADDRESS, 0);
                return new TranslationUnit(tuPointer, arena, index);
            } catch (ParseException e) {
                throw e;
            } catch (Throwable t) {
                arena.close();
                throw new RuntimeException("Failed to parse translation unit", t);
            }
        }
    }
}
