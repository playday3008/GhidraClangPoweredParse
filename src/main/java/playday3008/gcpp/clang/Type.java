package playday3008.gcpp.clang;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Represents a type in the Clang AST.
 * <p>
 * Wraps a {@code CXType} struct (passed by value as a MemorySegment).
 */
public final class Type {

    private final MemorySegment segment;
    private final Arena arena;

    /**
     * Creates a Type wrapping the given CXType segment.
     *
     * @param segment the CXType memory segment (must be CX_TYPE.byteSize() bytes)
     * @param arena   the arena used for allocating return-by-value structs
     */
    Type(MemorySegment segment, Arena arena) {
        this.segment = segment;
        this.arena = arena;
    }

    /**
     * Returns the underlying CXType memory segment.
     */
    MemorySegment getSegment() {
        return segment;
    }

    // ========================================================================
    // Type properties
    // ========================================================================

    /**
     * Return the kind of this type.
     */
    public TypeKind kind() {
        int kindVal = segment.get(ValueLayout.JAVA_INT, 0);
        return TypeKind.fromInteger(kindVal);
    }

    /**
     * Retrieve the spelling of this type.
     */
    public String spelling() {
        MemorySegment cxStr = LibClang.getTypeSpelling(arena, segment);
        return LibClang.extractString(arena, cxStr);
    }

    /**
     * Unwraps elaborated and attributed type wrappers to get the underlying type.
     * <p>
     * If this type's kind is ELABORATED, returns the named type (unwrapped recursively).
     * If this type's kind is ATTRIBUTED, returns the modified type (unwrapped recursively).
     * Otherwise returns this type.
     */
    public Type unwrap() {
        TypeKind k = kind();
        if (k == TypeKind.ELABORATED) {
            return namedType().unwrap();
        }
        if (k == TypeKind.ATTRIBUTED) {
            return modifiedType().unwrap();
        }
        return this;
    }

    /**
     * For pointer types, returns the type of the pointee.
     */
    public Type pointeeType() {
        MemorySegment typeSeg = LibClang.getPointeeType(arena, segment);
        return new Type(typeSeg, arena);
    }

    /**
     * For array types, returns the element type.
     */
    public Type arrayElementType() {
        MemorySegment typeSeg = LibClang.getArrayElementType(arena, segment);
        return new Type(typeSeg, arena);
    }

    /**
     * For constant array types, returns the number of elements.
     */
    public long arraySize() {
        return LibClang.getArraySize(segment);
    }

    /**
     * For function types, returns the return type.
     */
    public Type resultType() {
        MemorySegment typeSeg = LibClang.getResultType(arena, segment);
        return new Type(typeSeg, arena);
    }

    /**
     * For function types, returns the number of parameter types.
     */
    public int numArgTypes() {
        return LibClang.getNumArgTypes(segment);
    }

    /**
     * For function types, returns the parameter type at the given index.
     */
    public Type argType(int index) {
        MemorySegment typeSeg = LibClang.getArgType(arena, segment, index);
        return new Type(typeSeg, arena);
    }

    /**
     * Returns whether this function type is variadic.
     */
    public boolean isFunctionVariadic() {
        return LibClang.isFunctionTypeVariadic(segment) != 0;
    }

    /**
     * For function types, returns the calling convention.
     */
    public CallingConvention callingConvention() {
        int cc = LibClang.getFunctionTypeCallingConv(segment);
        return CallingConvention.fromInteger(cc);
    }

    /**
     * Returns the canonical type for this type.
     * <p>
     * Clang's type system considers types to be equivalent if they have
     * the same canonical form.
     */
    public Type canonicalType() {
        MemorySegment typeSeg = LibClang.getCanonicalType(arena, segment);
        return new Type(typeSeg, arena);
    }

    /**
     * For elaborated types, returns the named type (the type that the
     * elaborated type refers to).
     */
    public Type namedType() {
        MemorySegment typeSeg = LibClang.typeGetNamedType(arena, segment);
        return new Type(typeSeg, arena);
    }

    /**
     * For attributed types, returns the modified type (the type that the
     * attribute modifies).
     */
    public Type modifiedType() {
        MemorySegment typeSeg = LibClang.typeGetModifiedType(arena, segment);
        return new Type(typeSeg, arena);
    }

    /**
     * Retrieve the size of this type in bytes, or a negative value if the
     * size cannot be determined.
     */
    public long sizeOf() {
        return LibClang.typeGetSizeOf(segment);
    }

    /**
     * Retrieve the alignment of this type in bytes, or a negative value if
     * the alignment cannot be determined.
     */
    public long alignOf() {
        return LibClang.typeGetAlignOf(segment);
    }

    /**
     * Return the cursor for the declaration of this type.
     */
    public Cursor declaration() {
        MemorySegment cursorSeg = LibClang.getTypeDeclaration(arena, segment);
        return new Cursor(cursorSeg, arena);
    }

    /**
     * Returns the typedef name of this type, or an empty string if not a typedef.
     */
    public String getTypedefName() {
        MemorySegment cxStr = LibClang.getTypedefName(arena, segment);
        return LibClang.extractString(arena, cxStr);
    }

    @Override
    public String toString() {
        return "Type{kind=" + kind() + ", spelling='" + spelling() + "'}";
    }
}
