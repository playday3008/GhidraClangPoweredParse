package playday3008.gcpp.clang;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Static utility class providing all native interactions with libclang via Panama FFI.
 * All downcall handles and struct layouts are defined here.
 */
public final class LibClang {
    private LibClang() {}

    // ========================================================================
    // Struct Layouts
    // ========================================================================

    /** CXString: { void *data; unsigned private_flags; } = 16 bytes */
    static final StructLayout CX_STRING = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("data"),
        ValueLayout.JAVA_INT.withName("private_flags"),
        MemoryLayout.paddingLayout(4)
    );

    /** CXType: { enum CXTypeKind kind; void *data[2]; } = 24 bytes */
    static final StructLayout CX_TYPE = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("kind"),
        MemoryLayout.paddingLayout(4),
        MemoryLayout.sequenceLayout(2, ValueLayout.ADDRESS).withName("data")
    );

    /** CXCursor: { enum CXCursorKind kind; int xdata; const void *data[3]; } = 32 bytes */
    static final StructLayout CX_CURSOR = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("kind"),
        ValueLayout.JAVA_INT.withName("xdata"),
        MemoryLayout.sequenceLayout(3, ValueLayout.ADDRESS).withName("data")
    );

    /** CXSourceLocation: { const void *ptr_data[2]; unsigned int_data; } = 24 bytes */
    static final StructLayout CX_SOURCE_LOCATION = MemoryLayout.structLayout(
        MemoryLayout.sequenceLayout(2, ValueLayout.ADDRESS).withName("ptr_data"),
        ValueLayout.JAVA_INT.withName("int_data"),
        MemoryLayout.paddingLayout(4)
    );

    /** CXUnsavedFile: { const char *Filename; const char *Contents; unsigned long Length; } = 24 bytes */
    static final StructLayout CX_UNSAVED_FILE = MemoryLayout.structLayout(
        ValueLayout.ADDRESS.withName("Filename"),
        ValueLayout.ADDRESS.withName("Contents"),
        ValueLayout.JAVA_LONG.withName("Length")
    );

    /** Upcall descriptor for CXCursorVisitor callback */
    static final FunctionDescriptor VISITOR_DESC = FunctionDescriptor.of(
        ValueLayout.JAVA_INT,  // return: CXChildVisitResult (enum int)
        CX_CURSOR,             // cursor (by value)
        CX_CURSOR,             // parent (by value)
        ValueLayout.ADDRESS    // client_data
    );

    // ========================================================================
    // Library Loading
    // ========================================================================

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LOOKUP;

    static {
        LOOKUP = loadLibClang();
    }

    private static SymbolLookup loadLibClang() {
        String osName = System.getProperty("os.name", "").toLowerCase();

        if (osName.contains("win")) {
            return loadWindowsLibClang();
        } else if (osName.contains("mac")) {
            return loadMacLibClang();
        } else {
            return loadLinuxLibClang();
        }
    }

    private static SymbolLookup loadWindowsLibClang() {
        // Try standard library name on PATH
        String[] names = { "libclang", "libclang.dll" };
        for (String name : names) {
            try {
                return SymbolLookup.libraryLookup(name, Arena.global());
            } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {}
        }
        throw new RuntimeException(
            "Could not load libclang on Windows. Ensure libclang.dll is on the system PATH.");
    }

    private static SymbolLookup loadMacLibClang() {
        // Try Xcode path first
        String xcodeClang = "/Applications/Xcode.app/Contents/Developer/Toolchains/" +
            "XcodeDefault.xctoolchain/usr/lib/libclang.dylib";
        try {
            return SymbolLookup.libraryLookup(xcodeClang, Arena.global());
        } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {}

        // Try Homebrew LLVM
        String[] brewPaths = {
            "/opt/homebrew/opt/llvm/lib/libclang.dylib",
            "/usr/local/opt/llvm/lib/libclang.dylib"
        };
        for (String path : brewPaths) {
            try {
                return SymbolLookup.libraryLookup(path, Arena.global());
            } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {}
        }

        // Try system library name
        try {
            return SymbolLookup.libraryLookup("libclang.dylib", Arena.global());
        } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {}

        throw new RuntimeException(
            "Could not load libclang on macOS. Install Xcode or LLVM (e.g. via Homebrew).");
    }

    private static SymbolLookup loadLinuxLibClang() {
        // Try common versioned names first
        String[] names = {
            "libclang.so",
            "libclang-18.so", "libclang-17.so", "libclang-16.so",
            "libclang-15.so", "libclang-14.so"
        };
        for (String name : names) {
            try {
                return SymbolLookup.libraryLookup(name, Arena.global());
            } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {}
        }

        // Search /usr/lib/llvm-*/lib/ directories
        List<Path> candidates = new ArrayList<>();
        try {
            Path usrLib = Path.of("/usr/lib");
            if (Files.isDirectory(usrLib)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(usrLib, "llvm-*")) {
                    for (Path llvmDir : stream) {
                        Path libDir = llvmDir.resolve("lib");
                        if (Files.isDirectory(libDir)) {
                            try (DirectoryStream<Path> libs = Files.newDirectoryStream(libDir, "libclang.so*")) {
                                for (Path lib : libs) {
                                    candidates.add(lib);
                                }
                            }
                            // Also check for libclang-*.so
                            try (DirectoryStream<Path> libs = Files.newDirectoryStream(libDir, "libclang-*.so*")) {
                                for (Path lib : libs) {
                                    candidates.add(lib);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Also search /usr/lib64 and /usr/lib/x86_64-linux-gnu
        String[] extraDirs = { "/usr/lib64", "/usr/lib/x86_64-linux-gnu" };
        for (String dir : extraDirs) {
            try {
                Path dirPath = Path.of(dir);
                if (Files.isDirectory(dirPath)) {
                    try (DirectoryStream<Path> libs = Files.newDirectoryStream(dirPath, "libclang.so*")) {
                        for (Path lib : libs) {
                            candidates.add(lib);
                        }
                    }
                    try (DirectoryStream<Path> libs = Files.newDirectoryStream(dirPath, "libclang-*.so*")) {
                        for (Path lib : libs) {
                            candidates.add(lib);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Sort by name descending to prefer higher versions
        candidates.sort(Comparator.comparing(Path::toString).reversed());

        for (Path candidate : candidates) {
            try {
                return SymbolLookup.libraryLookup(candidate, Arena.global());
            } catch (IllegalArgumentException | UnsatisfiedLinkError ignored) {}
        }

        throw new RuntimeException(
            "Could not load libclang on Linux. Install libclang (e.g. 'apt install libclang-dev' " +
            "or 'dnf install clang-libs').");
    }

    // ========================================================================
    // Downcall MethodHandles
    // ========================================================================

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
            LOOKUP.find(name).orElseThrow(() ->
                new RuntimeException("Symbol not found in libclang: " + name)),
            desc
        );
    }

    // --- Core lifecycle ---

    private static final MethodHandle CLANG_CREATE_INDEX = downcall("clang_createIndex",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    private static final MethodHandle CLANG_DISPOSE_INDEX = downcall("clang_disposeIndex",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle CLANG_PARSE_TRANSLATION_UNIT_2 = downcall("clang_parseTranslationUnit2",
        FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,  // CXIndex
            ValueLayout.ADDRESS,  // source_filename
            ValueLayout.ADDRESS,  // command_line_args
            ValueLayout.JAVA_INT, // num_command_line_args
            ValueLayout.ADDRESS,  // unsaved_files
            ValueLayout.JAVA_INT, // num_unsaved_files
            ValueLayout.JAVA_INT, // options
            ValueLayout.ADDRESS   // out_TU (CXTranslationUnit*)
        ));

    private static final MethodHandle CLANG_DISPOSE_TRANSLATION_UNIT = downcall("clang_disposeTranslationUnit",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    // --- String handling ---

    private static final MethodHandle CLANG_GET_C_STRING = downcall("clang_getCString",
        FunctionDescriptor.of(ValueLayout.ADDRESS, CX_STRING));

    private static final MethodHandle CLANG_DISPOSE_STRING = downcall("clang_disposeString",
        FunctionDescriptor.ofVoid(CX_STRING));

    // --- Cursor operations ---

    private static final MethodHandle CLANG_GET_TRANSLATION_UNIT_CURSOR = downcall("clang_getTranslationUnitCursor",
        FunctionDescriptor.of(CX_CURSOR, ValueLayout.ADDRESS));

    private static final MethodHandle CLANG_GET_CURSOR_KIND = downcall("clang_getCursorKind",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, CX_CURSOR));

    private static final MethodHandle CLANG_GET_CURSOR_SPELLING = downcall("clang_getCursorSpelling",
        FunctionDescriptor.of(CX_STRING, CX_CURSOR));

    private static final MethodHandle CLANG_GET_CURSOR_DISPLAY_NAME = downcall("clang_getCursorDisplayName",
        FunctionDescriptor.of(CX_STRING, CX_CURSOR));

    private static final MethodHandle CLANG_GET_CURSOR_TYPE = downcall("clang_getCursorType",
        FunctionDescriptor.of(CX_TYPE, CX_CURSOR));

    private static final MethodHandle CLANG_GET_TYPEDEF_DECL_UNDERLYING_TYPE = downcall("clang_getTypedefDeclUnderlyingType",
        FunctionDescriptor.of(CX_TYPE, CX_CURSOR));

    private static final MethodHandle CLANG_GET_ENUM_DECL_INTEGER_TYPE = downcall("clang_getEnumDeclIntegerType",
        FunctionDescriptor.of(CX_TYPE, CX_CURSOR));

    private static final MethodHandle CLANG_GET_ENUM_CONSTANT_DECL_VALUE = downcall("clang_getEnumConstantDeclValue",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, CX_CURSOR));

    private static final MethodHandle CLANG_GET_ENUM_CONSTANT_DECL_UNSIGNED_VALUE = downcall("clang_getEnumConstantDeclUnsignedValue",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, CX_CURSOR));

    private static final MethodHandle CLANG_GET_CURSOR_LOCATION = downcall("clang_getCursorLocation",
        FunctionDescriptor.of(CX_SOURCE_LOCATION, CX_CURSOR));

    private static final MethodHandle CLANG_GET_CURSOR_SEMANTIC_PARENT = downcall("clang_getCursorSemanticParent",
        FunctionDescriptor.of(CX_CURSOR, CX_CURSOR));

    private static final MethodHandle CLANG_VISIT_CHILDREN = downcall("clang_visitChildren",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, CX_CURSOR, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle CLANG_EQUAL_CURSORS = downcall("clang_equalCursors",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, CX_CURSOR, CX_CURSOR));

    private static final MethodHandle CLANG_HASH_CURSOR = downcall("clang_hashCursor",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, CX_CURSOR));

    private static final MethodHandle CLANG_CURSOR_IS_BIT_FIELD = downcall("clang_Cursor_isBitField",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, CX_CURSOR));

    private static final MethodHandle CLANG_GET_FIELD_DECL_BIT_WIDTH = downcall("clang_getFieldDeclBitWidth",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, CX_CURSOR));

    private static final MethodHandle CLANG_CURSOR_GET_OFFSET_OF_FIELD = downcall("clang_Cursor_getOffsetOfField",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, CX_CURSOR));

    private static final MethodHandle CLANG_CURSOR_IS_ANONYMOUS = downcall("clang_Cursor_isAnonymous",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, CX_CURSOR));

    private static final MethodHandle CLANG_CURSOR_IS_ANONYMOUS_RECORD_DECL = downcall("clang_Cursor_isAnonymousRecordDecl",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, CX_CURSOR));

    private static final MethodHandle CLANG_IS_DECLARATION = downcall("clang_isDeclaration",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    // --- Type operations ---

    private static final MethodHandle CLANG_GET_TYPE_SPELLING = downcall("clang_getTypeSpelling",
        FunctionDescriptor.of(CX_STRING, CX_TYPE));

    private static final MethodHandle CLANG_GET_TYPE_DECLARATION = downcall("clang_getTypeDeclaration",
        FunctionDescriptor.of(CX_CURSOR, CX_TYPE));

    private static final MethodHandle CLANG_TYPE_GET_SIZE_OF = downcall("clang_Type_getSizeOf",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, CX_TYPE));

    private static final MethodHandle CLANG_TYPE_GET_ALIGN_OF = downcall("clang_Type_getAlignOf",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, CX_TYPE));

    private static final MethodHandle CLANG_GET_CANONICAL_TYPE = downcall("clang_getCanonicalType",
        FunctionDescriptor.of(CX_TYPE, CX_TYPE));

    private static final MethodHandle CLANG_TYPE_GET_NAMED_TYPE = downcall("clang_Type_getNamedType",
        FunctionDescriptor.of(CX_TYPE, CX_TYPE));

    private static final MethodHandle CLANG_TYPE_GET_MODIFIED_TYPE = downcall("clang_Type_getModifiedType",
        FunctionDescriptor.of(CX_TYPE, CX_TYPE));

    private static final MethodHandle CLANG_GET_POINTEE_TYPE = downcall("clang_getPointeeType",
        FunctionDescriptor.of(CX_TYPE, CX_TYPE));

    private static final MethodHandle CLANG_GET_ARRAY_ELEMENT_TYPE = downcall("clang_getArrayElementType",
        FunctionDescriptor.of(CX_TYPE, CX_TYPE));

    private static final MethodHandle CLANG_GET_ARRAY_SIZE = downcall("clang_getArraySize",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, CX_TYPE));

    private static final MethodHandle CLANG_GET_RESULT_TYPE = downcall("clang_getResultType",
        FunctionDescriptor.of(CX_TYPE, CX_TYPE));

    private static final MethodHandle CLANG_GET_NUM_ARG_TYPES = downcall("clang_getNumArgTypes",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, CX_TYPE));

    private static final MethodHandle CLANG_GET_ARG_TYPE = downcall("clang_getArgType",
        FunctionDescriptor.of(CX_TYPE, CX_TYPE, ValueLayout.JAVA_INT));

    private static final MethodHandle CLANG_IS_FUNCTION_TYPE_VARIADIC = downcall("clang_isFunctionTypeVariadic",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, CX_TYPE));

    private static final MethodHandle CLANG_GET_FUNCTION_TYPE_CALLING_CONV = downcall("clang_getFunctionTypeCallingConv",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, CX_TYPE));

    private static final MethodHandle CLANG_GET_TYPEDEF_NAME = downcall("clang_getTypedefName",
        FunctionDescriptor.of(CX_STRING, CX_TYPE));

    // --- Diagnostics ---

    private static final MethodHandle CLANG_GET_NUM_DIAGNOSTICS = downcall("clang_getNumDiagnostics",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle CLANG_GET_DIAGNOSTIC = downcall("clang_getDiagnostic",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private static final MethodHandle CLANG_DISPOSE_DIAGNOSTIC = downcall("clang_disposeDiagnostic",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle CLANG_GET_DIAGNOSTIC_SEVERITY = downcall("clang_getDiagnosticSeverity",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle CLANG_FORMAT_DIAGNOSTIC = downcall("clang_formatDiagnostic",
        FunctionDescriptor.of(CX_STRING, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private static final MethodHandle CLANG_DEFAULT_DIAGNOSTIC_DISPLAY_OPTIONS = downcall("clang_defaultDiagnosticDisplayOptions",
        FunctionDescriptor.of(ValueLayout.JAVA_INT));

    // --- Source location ---

    private static final MethodHandle CLANG_GET_FILE_LOCATION = downcall("clang_getFileLocation",
        FunctionDescriptor.ofVoid(
            CX_SOURCE_LOCATION,   // location (by value)
            ValueLayout.ADDRESS,  // file*
            ValueLayout.ADDRESS,  // line*
            ValueLayout.ADDRESS,  // column*
            ValueLayout.ADDRESS   // offset*
        ));

    private static final MethodHandle CLANG_GET_FILE_NAME = downcall("clang_getFileName",
        FunctionDescriptor.of(CX_STRING, ValueLayout.ADDRESS));

    private static final MethodHandle CLANG_LOCATION_IS_IN_SYSTEM_HEADER = downcall("clang_Location_isInSystemHeader",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, CX_SOURCE_LOCATION));

    // ========================================================================
    // CXString helper
    // ========================================================================

    /**
     * Extracts a Java String from a CXString MemorySegment, then disposes the CXString.
     * @param allocator allocator for temporary CXString storage
     * @param cxString the CXString segment (must be CX_STRING.byteSize() bytes)
     * @return the Java String, or empty string if the C string pointer is NULL
     */
    public static String extractString(SegmentAllocator allocator, MemorySegment cxString) {
        try {
            MemorySegment cStr = getCString(cxString);
            if (cStr.equals(MemorySegment.NULL)) {
                return "";
            }
            return cStr.reinterpret(Long.MAX_VALUE).getUtf8String(0);
        } finally {
            disposeString(cxString);
        }
    }

    // ========================================================================
    // Public static wrapper methods
    // ========================================================================

    // --- Core lifecycle ---

    public static MemorySegment createIndex(int excludeDeclarationsFromPCH, int displayDiagnostics) {
        try {
            return (MemorySegment) CLANG_CREATE_INDEX.invokeExact(excludeDeclarationsFromPCH, displayDiagnostics);
        } catch (Throwable t) {
            throw new RuntimeException("clang_createIndex failed", t);
        }
    }

    public static void disposeIndex(MemorySegment index) {
        try {
            CLANG_DISPOSE_INDEX.invokeExact(index);
        } catch (Throwable t) {
            throw new RuntimeException("clang_disposeIndex failed", t);
        }
    }

    public static int parseTranslationUnit2(MemorySegment index, MemorySegment sourceFilename,
            MemorySegment commandLineArgs, int numCommandLineArgs,
            MemorySegment unsavedFiles, int numUnsavedFiles,
            int options, MemorySegment outTU) {
        try {
            return (int) CLANG_PARSE_TRANSLATION_UNIT_2.invokeExact(
                index, sourceFilename, commandLineArgs, numCommandLineArgs,
                unsavedFiles, numUnsavedFiles, options, outTU);
        } catch (Throwable t) {
            throw new RuntimeException("clang_parseTranslationUnit2 failed", t);
        }
    }

    public static void disposeTranslationUnit(MemorySegment tu) {
        try {
            CLANG_DISPOSE_TRANSLATION_UNIT.invokeExact(tu);
        } catch (Throwable t) {
            throw new RuntimeException("clang_disposeTranslationUnit failed", t);
        }
    }

    // --- String handling ---

    public static MemorySegment getCString(MemorySegment cxString) {
        try {
            return (MemorySegment) CLANG_GET_C_STRING.invokeExact(cxString);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getCString failed", t);
        }
    }

    public static void disposeString(MemorySegment cxString) {
        try {
            CLANG_DISPOSE_STRING.invokeExact(cxString);
        } catch (Throwable t) {
            throw new RuntimeException("clang_disposeString failed", t);
        }
    }

    // --- Cursor operations ---

    public static MemorySegment getTranslationUnitCursor(SegmentAllocator allocator, MemorySegment tu) {
        try {
            return (MemorySegment) CLANG_GET_TRANSLATION_UNIT_CURSOR.invokeExact(allocator, tu);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getTranslationUnitCursor failed", t);
        }
    }

    public static int getCursorKind(MemorySegment cursor) {
        try {
            return (int) CLANG_GET_CURSOR_KIND.invokeExact(cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getCursorKind failed", t);
        }
    }

    public static MemorySegment getCursorSpelling(SegmentAllocator allocator, MemorySegment cursor) {
        try {
            return (MemorySegment) CLANG_GET_CURSOR_SPELLING.invokeExact(allocator, cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getCursorSpelling failed", t);
        }
    }

    public static MemorySegment getCursorDisplayName(SegmentAllocator allocator, MemorySegment cursor) {
        try {
            return (MemorySegment) CLANG_GET_CURSOR_DISPLAY_NAME.invokeExact(allocator, cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getCursorDisplayName failed", t);
        }
    }

    public static MemorySegment getCursorType(SegmentAllocator allocator, MemorySegment cursor) {
        try {
            return (MemorySegment) CLANG_GET_CURSOR_TYPE.invokeExact(allocator, cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getCursorType failed", t);
        }
    }

    public static MemorySegment getTypedefDeclUnderlyingType(SegmentAllocator allocator, MemorySegment cursor) {
        try {
            return (MemorySegment) CLANG_GET_TYPEDEF_DECL_UNDERLYING_TYPE.invokeExact(allocator, cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getTypedefDeclUnderlyingType failed", t);
        }
    }

    public static MemorySegment getEnumDeclIntegerType(SegmentAllocator allocator, MemorySegment cursor) {
        try {
            return (MemorySegment) CLANG_GET_ENUM_DECL_INTEGER_TYPE.invokeExact(allocator, cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getEnumDeclIntegerType failed", t);
        }
    }

    public static long getEnumConstantDeclValue(MemorySegment cursor) {
        try {
            return (long) CLANG_GET_ENUM_CONSTANT_DECL_VALUE.invokeExact(cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getEnumConstantDeclValue failed", t);
        }
    }

    public static long getEnumConstantDeclUnsignedValue(MemorySegment cursor) {
        try {
            return (long) CLANG_GET_ENUM_CONSTANT_DECL_UNSIGNED_VALUE.invokeExact(cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getEnumConstantDeclUnsignedValue failed", t);
        }
    }

    public static MemorySegment getCursorLocation(SegmentAllocator allocator, MemorySegment cursor) {
        try {
            return (MemorySegment) CLANG_GET_CURSOR_LOCATION.invokeExact(allocator, cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getCursorLocation failed", t);
        }
    }

    public static MemorySegment getCursorSemanticParent(SegmentAllocator allocator, MemorySegment cursor) {
        try {
            return (MemorySegment) CLANG_GET_CURSOR_SEMANTIC_PARENT.invokeExact(allocator, cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getCursorSemanticParent failed", t);
        }
    }

    public static int visitChildren(MemorySegment parent, MemorySegment visitor, MemorySegment clientData) {
        try {
            return (int) CLANG_VISIT_CHILDREN.invokeExact(parent, visitor, clientData);
        } catch (Throwable t) {
            throw new RuntimeException("clang_visitChildren failed", t);
        }
    }

    public static int equalCursors(MemorySegment c1, MemorySegment c2) {
        try {
            return (int) CLANG_EQUAL_CURSORS.invokeExact(c1, c2);
        } catch (Throwable t) {
            throw new RuntimeException("clang_equalCursors failed", t);
        }
    }

    public static int hashCursor(MemorySegment cursor) {
        try {
            return (int) CLANG_HASH_CURSOR.invokeExact(cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_hashCursor failed", t);
        }
    }

    public static int cursorIsBitField(MemorySegment cursor) {
        try {
            return (int) CLANG_CURSOR_IS_BIT_FIELD.invokeExact(cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_Cursor_isBitField failed", t);
        }
    }

    public static int getFieldDeclBitWidth(MemorySegment cursor) {
        try {
            return (int) CLANG_GET_FIELD_DECL_BIT_WIDTH.invokeExact(cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getFieldDeclBitWidth failed", t);
        }
    }

    public static long cursorGetOffsetOfField(MemorySegment cursor) {
        try {
            return (long) CLANG_CURSOR_GET_OFFSET_OF_FIELD.invokeExact(cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_Cursor_getOffsetOfField failed", t);
        }
    }

    public static int cursorIsAnonymous(MemorySegment cursor) {
        try {
            return (int) CLANG_CURSOR_IS_ANONYMOUS.invokeExact(cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_Cursor_isAnonymous failed", t);
        }
    }

    public static int cursorIsAnonymousRecordDecl(MemorySegment cursor) {
        try {
            return (int) CLANG_CURSOR_IS_ANONYMOUS_RECORD_DECL.invokeExact(cursor);
        } catch (Throwable t) {
            throw new RuntimeException("clang_Cursor_isAnonymousRecordDecl failed", t);
        }
    }

    public static int isDeclaration(int cursorKind) {
        try {
            return (int) CLANG_IS_DECLARATION.invokeExact(cursorKind);
        } catch (Throwable t) {
            throw new RuntimeException("clang_isDeclaration failed", t);
        }
    }

    // --- Type operations ---

    public static MemorySegment getTypeSpelling(SegmentAllocator allocator, MemorySegment type) {
        try {
            return (MemorySegment) CLANG_GET_TYPE_SPELLING.invokeExact(allocator, type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getTypeSpelling failed", t);
        }
    }

    public static MemorySegment getTypeDeclaration(SegmentAllocator allocator, MemorySegment type) {
        try {
            return (MemorySegment) CLANG_GET_TYPE_DECLARATION.invokeExact(allocator, type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getTypeDeclaration failed", t);
        }
    }

    public static long typeGetSizeOf(MemorySegment type) {
        try {
            return (long) CLANG_TYPE_GET_SIZE_OF.invokeExact(type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_Type_getSizeOf failed", t);
        }
    }

    public static long typeGetAlignOf(MemorySegment type) {
        try {
            return (long) CLANG_TYPE_GET_ALIGN_OF.invokeExact(type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_Type_getAlignOf failed", t);
        }
    }

    public static MemorySegment getCanonicalType(SegmentAllocator allocator, MemorySegment type) {
        try {
            return (MemorySegment) CLANG_GET_CANONICAL_TYPE.invokeExact(allocator, type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getCanonicalType failed", t);
        }
    }

    public static MemorySegment typeGetNamedType(SegmentAllocator allocator, MemorySegment type) {
        try {
            return (MemorySegment) CLANG_TYPE_GET_NAMED_TYPE.invokeExact(allocator, type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_Type_getNamedType failed", t);
        }
    }

    public static MemorySegment typeGetModifiedType(SegmentAllocator allocator, MemorySegment type) {
        try {
            return (MemorySegment) CLANG_TYPE_GET_MODIFIED_TYPE.invokeExact(allocator, type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_Type_getModifiedType failed", t);
        }
    }

    public static MemorySegment getPointeeType(SegmentAllocator allocator, MemorySegment type) {
        try {
            return (MemorySegment) CLANG_GET_POINTEE_TYPE.invokeExact(allocator, type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getPointeeType failed", t);
        }
    }

    public static MemorySegment getArrayElementType(SegmentAllocator allocator, MemorySegment type) {
        try {
            return (MemorySegment) CLANG_GET_ARRAY_ELEMENT_TYPE.invokeExact(allocator, type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getArrayElementType failed", t);
        }
    }

    public static long getArraySize(MemorySegment type) {
        try {
            return (long) CLANG_GET_ARRAY_SIZE.invokeExact(type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getArraySize failed", t);
        }
    }

    public static MemorySegment getResultType(SegmentAllocator allocator, MemorySegment type) {
        try {
            return (MemorySegment) CLANG_GET_RESULT_TYPE.invokeExact(allocator, type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getResultType failed", t);
        }
    }

    public static int getNumArgTypes(MemorySegment type) {
        try {
            return (int) CLANG_GET_NUM_ARG_TYPES.invokeExact(type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getNumArgTypes failed", t);
        }
    }

    public static MemorySegment getArgType(SegmentAllocator allocator, MemorySegment type, int index) {
        try {
            return (MemorySegment) CLANG_GET_ARG_TYPE.invokeExact(allocator, type, index);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getArgType failed", t);
        }
    }

    public static int isFunctionTypeVariadic(MemorySegment type) {
        try {
            return (int) CLANG_IS_FUNCTION_TYPE_VARIADIC.invokeExact(type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_isFunctionTypeVariadic failed", t);
        }
    }

    public static int getFunctionTypeCallingConv(MemorySegment type) {
        try {
            return (int) CLANG_GET_FUNCTION_TYPE_CALLING_CONV.invokeExact(type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getFunctionTypeCallingConv failed", t);
        }
    }

    public static MemorySegment getTypedefName(SegmentAllocator allocator, MemorySegment type) {
        try {
            return (MemorySegment) CLANG_GET_TYPEDEF_NAME.invokeExact(allocator, type);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getTypedefName failed", t);
        }
    }

    // --- Diagnostics ---

    public static int getNumDiagnostics(MemorySegment tu) {
        try {
            return (int) CLANG_GET_NUM_DIAGNOSTICS.invokeExact(tu);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getNumDiagnostics failed", t);
        }
    }

    public static MemorySegment getDiagnostic(MemorySegment tu, int index) {
        try {
            return (MemorySegment) CLANG_GET_DIAGNOSTIC.invokeExact(tu, index);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getDiagnostic failed", t);
        }
    }

    public static void disposeDiagnostic(MemorySegment diag) {
        try {
            CLANG_DISPOSE_DIAGNOSTIC.invokeExact(diag);
        } catch (Throwable t) {
            throw new RuntimeException("clang_disposeDiagnostic failed", t);
        }
    }

    public static int getDiagnosticSeverity(MemorySegment diag) {
        try {
            return (int) CLANG_GET_DIAGNOSTIC_SEVERITY.invokeExact(diag);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getDiagnosticSeverity failed", t);
        }
    }

    public static MemorySegment formatDiagnostic(SegmentAllocator allocator, MemorySegment diag, int options) {
        try {
            return (MemorySegment) CLANG_FORMAT_DIAGNOSTIC.invokeExact(allocator, diag, options);
        } catch (Throwable t) {
            throw new RuntimeException("clang_formatDiagnostic failed", t);
        }
    }

    public static int defaultDiagnosticDisplayOptions() {
        try {
            return (int) CLANG_DEFAULT_DIAGNOSTIC_DISPLAY_OPTIONS.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("clang_defaultDiagnosticDisplayOptions failed", t);
        }
    }

    // --- Source location ---

    public static void getFileLocation(MemorySegment location, MemorySegment file,
            MemorySegment line, MemorySegment column, MemorySegment offset) {
        try {
            CLANG_GET_FILE_LOCATION.invokeExact(location, file, line, column, offset);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getFileLocation failed", t);
        }
    }

    public static MemorySegment getFileName(SegmentAllocator allocator, MemorySegment file) {
        try {
            return (MemorySegment) CLANG_GET_FILE_NAME.invokeExact(allocator, file);
        } catch (Throwable t) {
            throw new RuntimeException("clang_getFileName failed", t);
        }
    }

    public static int isInSystemHeader(MemorySegment location) {
        try {
            return (int) CLANG_LOCATION_IS_IN_SYSTEM_HEADER.invokeExact(location);
        } catch (Throwable t) {
            throw new RuntimeException("clang_Location_isInSystemHeader failed", t);
        }
    }

    // ========================================================================
    // Expose linker for upcall stubs
    // ========================================================================

    static Linker linker() {
        return LINKER;
    }
}
