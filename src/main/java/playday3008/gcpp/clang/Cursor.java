package playday3008.gcpp.clang;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

/**
 * A cursor representing some element in the abstract syntax tree for
 * a translation unit.
 * <p>
 * The cursor abstraction unifies the different kinds of entities in a
 * program (declarations, statements, expressions, references to declarations,
 * etc.) under a single "cursor" abstraction with a common set of operations.
 * <p>
 * Wraps a {@code CXCursor} struct (passed by value as a MemorySegment).
 */
public final class Cursor {

    private final MemorySegment segment;
    private final Arena arena;

    // Thread-locals for visitor callback bridge
    private static final ThreadLocal<CursorVisitor> CURRENT_VISITOR = new ThreadLocal<>();
    private static final ThreadLocal<Arena> CURRENT_ARENA = new ThreadLocal<>();

    /**
     * Creates a Cursor wrapping the given CXCursor segment.
     *
     * @param segment the CXCursor memory segment (must be CX_CURSOR.byteSize() bytes)
     * @param arena   the arena used for allocating return-by-value structs
     */
    Cursor(MemorySegment segment, Arena arena) {
        this.segment = segment;
        this.arena = arena;
    }

    /**
     * Returns the underlying CXCursor memory segment.
     */
    MemorySegment getSegment() {
        return segment;
    }

    // ========================================================================
    // Cursor properties
    // ========================================================================

    /**
     * Return the kind of this cursor.
     */
    public CursorKind kind() {
        int kindVal = LibClang.getCursorKind(segment);
        return CursorKind.fromInteger(kindVal);
    }

    /**
     * Return the spelling of the entity pointed at by the cursor.
     */
    public String spelling() {
        MemorySegment cxStr = LibClang.getCursorSpelling(arena, segment);
        return LibClang.extractString(arena, cxStr);
    }

    /**
     * Return the display name for the entity referenced by this cursor.
     * <p>
     * The display name contains extra information that helps identify the
     * cursor, such as the parameters of a function or template or the
     * arguments of a class template specialization.
     */
    public String displayName() {
        MemorySegment cxStr = LibClang.getCursorDisplayName(arena, segment);
        return LibClang.extractString(arena, cxStr);
    }

    /**
     * Retrieve the Type (if any) of the entity pointed at by the cursor.
     */
    public Type type() {
        MemorySegment typeSeg = LibClang.getCursorType(arena, segment);
        return new Type(typeSeg, arena);
    }

    /**
     * Return the underlying type of a typedef declaration.
     */
    public Type underlyingTypedefType() {
        MemorySegment typeSeg = LibClang.getTypedefDeclUnderlyingType(arena, segment);
        return new Type(typeSeg, arena);
    }

    /**
     * Return the integer type of an enum declaration.
     */
    public Type enumType() {
        MemorySegment typeSeg = LibClang.getEnumDeclIntegerType(arena, segment);
        return new Type(typeSeg, arena);
    }

    /**
     * Return the value of an enum constant.
     * <p>
     * Automatically selects signed or unsigned retrieval based on the
     * underlying enum integer type.
     */
    public long enumValue() {
        Type underlyingType = this.type();

        // If the type is ENUM, get the enum's integer type
        if (underlyingType.kind() == TypeKind.ENUM) {
            underlyingType = underlyingType.declaration().enumType();
        }

        // Check if the underlying type is unsigned
        List<TypeKind> unsignedKinds = List.of(
            TypeKind.CHAR_U, TypeKind.U_CHAR, TypeKind.CHAR16, TypeKind.CHAR32,
            TypeKind.U_SHORT, TypeKind.U_INT, TypeKind.U_LONG, TypeKind.U_LONG_LONG,
            TypeKind.U_INT128
        );

        if (unsignedKinds.contains(underlyingType.kind())) {
            return LibClang.getEnumConstantDeclUnsignedValue(segment);
        } else {
            return LibClang.getEnumConstantDeclValue(segment);
        }
    }

    /**
     * Return the source location of this cursor.
     */
    public SourceLocation location() {
        MemorySegment locSeg = LibClang.getCursorLocation(arena, segment);
        return new SourceLocation(locSeg, arena);
    }

    /**
     * Return the semantic parent of this cursor.
     */
    public Cursor semanticParent() {
        MemorySegment parentSeg = LibClang.getCursorSemanticParent(arena, segment);
        return new Cursor(parentSeg, arena);
    }

    /**
     * Returns whether this cursor represents a bit-field declaration.
     */
    public boolean isBitField() {
        return LibClang.cursorIsBitField(segment) != 0;
    }

    /**
     * Returns the bit width of a bit-field declaration.
     */
    public int getBitFieldWidth() {
        return LibClang.getFieldDeclBitWidth(segment);
    }

    /**
     * Returns the offset of a field in bits.
     */
    public long getFieldOffset() {
        return LibClang.cursorGetOffsetOfField(segment);
    }

    /**
     * Returns whether this cursor represents an anonymous declaration.
     */
    public boolean isAnonymous() {
        return LibClang.cursorIsAnonymous(segment) != 0;
    }

    /**
     * Returns whether this cursor represents an anonymous record declaration.
     */
    public boolean isAnonymousRecordDecl() {
        return LibClang.cursorIsAnonymousRecordDecl(segment) != 0;
    }

    // ========================================================================
    // Visitor pattern
    // ========================================================================

    /**
     * Functional interface for visiting child cursors.
     */
    @FunctionalInterface
    public interface CursorVisitor {
        ChildVisitResult visit(Cursor cursor, Cursor parent);
    }

    /**
     * Result codes for cursor visitor callbacks.
     */
    public enum ChildVisitResult {
        /** Terminates the cursor traversal. */
        BREAK(0),
        /** Continues with the next sibling, without visiting children. */
        CONTINUE(1),
        /** Recursively traverse the children of this cursor. */
        RECURSE(2);

        final int value;

        ChildVisitResult(int v) {
            this.value = v;
        }
    }

    /**
     * Visit the children of this cursor, invoking the visitor for each child.
     *
     * @param visitor the visitor callback to invoke for each child
     */
    public void visitChildren(CursorVisitor visitor) {
        try (Arena upcallArena = Arena.ofConfined()) {
            MethodHandle callback;
            try {
                callback = MethodHandles.lookup().findStatic(
                    Cursor.class,
                    "visitChildrenCallback",
                    MethodType.methodType(int.class,
                        MemorySegment.class, MemorySegment.class, MemorySegment.class)
                );
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new RuntimeException("Failed to find visitChildrenCallback", e);
            }

            MemorySegment stub = LibClang.linker().upcallStub(
                callback,
                LibClang.VISITOR_DESC,
                upcallArena
            );

            // Save previous values for re-entrant (nested) visitChildren calls.
            // SourceParser nests visitors: outer callback -> parseStruct -> inner visitChildren.
            // Without save/restore, the inner finally block would remove() the ThreadLocal
            // values that the outer callback still needs, causing a null dereference.
            CursorVisitor previousVisitor = CURRENT_VISITOR.get();
            Arena previousArena = CURRENT_ARENA.get();
            CURRENT_VISITOR.set(visitor);
            CURRENT_ARENA.set(this.arena);
            try {
                LibClang.visitChildren(this.segment, stub, MemorySegment.NULL);
            } finally {
                // Restore previous values (not remove!) so outer callbacks keep working
                if (previousVisitor != null) {
                    CURRENT_VISITOR.set(previousVisitor);
                } else {
                    CURRENT_VISITOR.remove();
                }
                if (previousArena != null) {
                    CURRENT_ARENA.set(previousArena);
                } else {
                    CURRENT_ARENA.remove();
                }
            }
        }
    }

    /**
     * Native upcall target for clang_visitChildren. Receives raw MemorySegments
     * representing CXCursor structs passed by value from libclang.
     * <p>
     * CRITICAL: Catches ALL Throwable to prevent JVM termination from uncaught
     * exceptions in upcall stubs.
     */
    private static int visitChildrenCallback(MemorySegment cursorSeg,
                                             MemorySegment parentSeg,
                                             MemorySegment clientData) {
        try {
            Arena arena = CURRENT_ARENA.get();
            CursorVisitor visitor = CURRENT_VISITOR.get();

            // Copy callback-scoped segments into the TU's persistent arena.
            // The segments provided by Panama for struct-by-value upcall parameters
            // are only valid for the duration of this callback invocation.
            MemorySegment cursorCopy = arena.allocate(LibClang.CX_CURSOR);
            cursorCopy.copyFrom(cursorSeg.reinterpret(LibClang.CX_CURSOR.byteSize()));
            MemorySegment parentCopy = arena.allocate(LibClang.CX_CURSOR);
            parentCopy.copyFrom(parentSeg.reinterpret(LibClang.CX_CURSOR.byteSize()));

            Cursor cursor = new Cursor(cursorCopy, arena);
            Cursor parent = new Cursor(parentCopy, arena);
            return visitor.visit(cursor, parent).value;
        } catch (Throwable t) {
            // MUST catch everything -- uncaught exceptions in upcalls terminate the JVM
            t.printStackTrace();
            return ChildVisitResult.BREAK.value;
        }
    }

    // ========================================================================
    // Object methods
    // ========================================================================

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Cursor other)) return false;
        return LibClang.equalCursors(this.segment, other.segment) != 0;
    }

    @Override
    public int hashCode() {
        return LibClang.hashCursor(segment);
    }

    @Override
    public String toString() {
        CursorKind k = kind();
        String s = spelling();
        return "Cursor{kind=" + k + ", spelling='" + s + "'}";
    }
}
