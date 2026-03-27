package playday3008.gcpp.processing;

import ghidra.program.model.data.CategoryPath;
import playday3008.gcpp.clang.*;
import playday3008.gcpp.clang.error.ParseException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Parses C/C++ source files through libclang and populates a {@link TypePool}
 * with extracted type declarations.
 * <p>
 * Unlike the original GhidraClangTypes implementation, this uses a
 * <b>single recursive pass</b> over the AST. Panama FFI upcalls can nest
 * freely (JNA callbacks could not), so the two-pass workaround is no longer needed.
 * <p>
 * Features over the original:
 * <ul>
 *   <li>Bit-field support (via clang_Cursor_isBitField / clang_getFieldDeclBitWidth)</li>
 *   <li>Packed struct detection (via PACKED_ATTR cursor)</li>
 *   <li>Function signature extraction (return type, params, varargs, calling convention)</li>
 *   <li>Header filename &rarr; CategoryPath mapping (matches stock Ghidra parser)</li>
 *   <li>C++ namespace &rarr; CategoryPath subcategory</li>
 *   <li>System header filtering</li>
 *   <li>No hardcoded preamble typedefs</li>
 * </ul>
 */
public class SourceParser {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final String UMBRELLA_FILENAME = "__gcpp_umbrella.hpp";

    /**
     * Parse source files from disk through clang.
     * Generates a synthetic umbrella header that #includes all source files,
     * passes include paths and options as clang command-line args.
     *
     * @return list of diagnostic messages from clang (errors only)
     */
    public List<String> parseFiles(TypePool typePool, String[] sourceFiles,
                                   String[] includePaths, String options,
                                   String languageId, String compilerSpec) throws ParseException {
        typePool.clearParsedTypes();

        // Build synthetic umbrella header
        StringBuilder source = new StringBuilder();
        for (String file : sourceFiles) {
            String trimmed = file.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#"))
                source.append("#include \"").append(trimmed).append("\"\n");
        }

        // Build clang args from architecture + compiler
        List<String> args = new ArrayList<>(ArchitectureMapping.getClangArgs(languageId, compilerSpec));

        // Don't stop parsing after 20 errors -- we want as many types as possible
        args.add("-ferror-limit=0");
        // Suppress noisy warnings that don't affect type extraction
        args.add("-Wno-macro-redefined");
        args.add("-Wno-nonportable-include-path");
        args.add("-Wno-pragma-pack");
        args.add("-Wno-ignored-attributes");
        args.add("-Wno-ignored-pragma-intrinsic");
        args.add("-Wno-typedef-redefinition");
        args.add("-Wno-microsoft-redecl-typedef");

        // Include paths
        for (String path : includePaths) {
            String trimmed = path.trim();
            if (!trimmed.isEmpty())
                args.add("-I" + trimmed);
        }

        // Clean and filter profile parse options
        if (options != null) {
            for (String line : options.split("\\R")) {
                String cleaned = cleanProfileOption(line);
                if (cleaned != null)
                    args.add(cleaned);
            }
        }

        // Override defines AFTER user options (last -D wins).
        // Attribute-based SAL uses __declspec annotations that clang doesn't support,
        // causing hundreds of "undeclared identifier 'Name'" errors. Force macro-based SAL.
        args.add("-D_USE_ATTRIBUTES_FOR_SAL=0");

        // Windows SDK-specific defines to improve header compatibility.
        // Detect MSVC target from the args list (ArchitectureMapping sets --target=...-windows-msvc).
        if (args.stream().anyMatch(a -> a.contains("windows-msvc"))) {
            // Use C-style COM interfaces instead of C++ class inheritance.
            // Avoids "expected class name" errors from unresolved base classes (IUnknown etc.)
            // and produces struct definitions with explicit vtable pointers — more useful for RE.
            args.add("-DCINTERFACE");
            args.add("-DCOBJMACROS");
            // ntlsa.h and ntsecapi.h both define LSA types (POLICY_AUDIT_EVENT_TYPE, etc.)
            // MSVC allows identical struct redefinitions; clang doesn't. Setting _NTLSA_
            // tells ntsecapi.h that ntlsa.h was already included, preventing duplicates.
            args.add("-D_NTLSA_");
        }

        LOGGER.info("Parsing {} source file(s) with language={}, compiler={}", sourceFiles.length, languageId, compilerSpec);
        LOGGER.debug("Clang args: {}", args);

        List<String> diagnostics = new ArrayList<>();

        try (var index = Index.create(true);
             var tu = new TranslationUnit.Builder(UMBRELLA_FILENAME)
                 .unsavedFile(UMBRELLA_FILENAME, source.toString())
                 .args(args.toArray(new String[0]))
                 .parseIncomplete()
                 .skipFunctionBodies()
                 .keepGoing()
                 .includeAttributedTypes()
                 .visitImplicitAttributes()
                 .build(index)) {

            // Collect only error-level diagnostics (skip warnings/notes)
            int totalDiags = tu.getNumDiagnostics();
            for (int i = 0; i < totalDiags; i++) {
                try (var d = tu.getDiagnostic(i)) {
                    if (d.severity() >= Diagnostic.SEVERITY_ERROR)
                        diagnostics.add(d.format());
                }
            }
            if (!diagnostics.isEmpty()) {
                LOGGER.warn("Clang reported {} error(s) out of {} diagnostics", diagnostics.size(), totalDiags);
            }

            // Single recursive pass -- Panama upcalls nest freely
            visitDeclarations(tu.cursor(), CategoryPath.ROOT, typePool);
        }

        LOGGER.info("Parse complete: {} error diagnostic(s)", diagnostics.size());
        return diagnostics;
    }

    // ========================================================================
    // Recursive AST traversal
    // ========================================================================

    /**
     * Recursively visit declarations in the AST, creating ParsedType objects
     * and adding them to the TypePool.
     */
    private void visitDeclarations(Cursor cursor, CategoryPath category, TypePool pool) {
        cursor.visitChildren((child, parent) -> {
            // Skip declarations from system headers
            if (child.location().isInSystemHeader()) {
                return Cursor.ChildVisitResult.CONTINUE;
            }

            // Determine category from the source file's header name
            CategoryPath childCategory = getCategoryForCursor(child, category);

            CursorKind kind = child.kind();
            if (kind == null) {
                return Cursor.ChildVisitResult.CONTINUE;
            }

            switch (kind) {
                case STRUCT_DECL, CLASS_DECL -> {
                    parseStruct(pool, child, childCategory);
                    return Cursor.ChildVisitResult.CONTINUE;
                }
                case UNION_DECL -> {
                    parseUnion(pool, child, childCategory);
                    return Cursor.ChildVisitResult.CONTINUE;
                }
                case ENUM_DECL -> {
                    parseEnum(pool, child, childCategory);
                    return Cursor.ChildVisitResult.CONTINUE;
                }
                case TYPEDEF_DECL, TYPE_ALIAS_DECL -> {
                    parseTypedef(pool, child, childCategory);
                    return Cursor.ChildVisitResult.CONTINUE;
                }
                case FUNCTION_DECL -> {
                    parseFunction(pool, child, childCategory);
                    return Cursor.ChildVisitResult.CONTINUE;
                }
                case NAMESPACE -> {
                    String nsName = child.spelling();
                    CategoryPath nsCategory = (nsName == null || nsName.isEmpty())
                        ? childCategory
                        : new CategoryPath(childCategory, nsName);
                    visitDeclarations(child, nsCategory, pool);
                    return Cursor.ChildVisitResult.CONTINUE;
                }
                case UNEXPOSED_DECL, LINKAGE_SPEC -> {
                    return Cursor.ChildVisitResult.RECURSE;
                }
                default -> {
                    return Cursor.ChildVisitResult.CONTINUE;
                }
            }
        });
    }

    /**
     * Determines the CategoryPath for a cursor based on its source file location.
     * Uses the header filename as the category name (matching stock Ghidra parser behavior).
     */
    private CategoryPath getCategoryForCursor(Cursor cursor, CategoryPath fallback) {
        try {
            var loc = cursor.location();
            var fileLoc = loc.getFileLocation();
            String filename = fileLoc.filename();
            if (filename != null && !filename.isEmpty()) {
                String basename = Path.of(filename).getFileName().toString();
                // Skip our synthetic umbrella header
                if (!UMBRELLA_FILENAME.equals(basename)) {
                    return new CategoryPath(CategoryPath.ROOT, basename);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not determine category for cursor: {}", e.getMessage());
        }
        return fallback;
    }

    // ========================================================================
    // Type parsing methods
    // ========================================================================

    private void parseEnum(TypePool pool, Cursor enumCursor, CategoryPath category) {
        String name = enumCursor.spelling();
        if (name == null || name.isEmpty())
            return; // Skip anonymous enums

        long size = enumCursor.enumType().sizeOf();
        if (size <= 0)
            return; // Skip forward-declared / incomplete enums

        LinkedHashMap<String, Long> enumValues = new LinkedHashMap<>();

        enumCursor.visitChildren((cursor, parent) -> {
            if (cursor.kind() == CursorKind.ENUM_CONSTANT_DECL)
                enumValues.put(cursor.spelling(), cursor.enumValue());
            return Cursor.ChildVisitResult.CONTINUE;
        });

        pool.addParsedType(new ParsedEnum(name, (int) size, enumValues, category));
    }

    private void parseTypedef(TypePool pool, Cursor cursor, CategoryPath category) {
        String name = cursor.spelling();
        if (name == null || name.isEmpty())
            return;

        pool.addParsedType(new ParsedTypedef(name, cursor.underlyingTypedefType().spelling(), category));
    }

    private void parseStruct(TypePool pool, Cursor structCursor, CategoryPath category) {
        String name = structCursor.spelling();
        if (name == null || name.isEmpty())
            return; // Skip anonymous structs (they'll be handled inline as field types)

        List<ParsedStructure.FieldInfo> fields = new ArrayList<>();
        boolean[] packed = {false};

        structCursor.visitChildren((cursor, parent) -> {
            CursorKind kind = cursor.kind();
            if (kind == null) return Cursor.ChildVisitResult.CONTINUE;

            switch (kind) {
                case FIELD_DECL -> {
                    boolean isBitField = cursor.isBitField();
                    int bitWidth = isBitField ? cursor.getBitFieldWidth() : 0;
                    boolean isAnon = cursor.isAnonymous();

                    // For anonymous struct/union fields, recursively parse the inner type
                    ParsedType anonType = null;
                    if (isAnon) {
                        Type fieldType = cursor.type().unwrap();
                        TypeKind typeKind = fieldType.kind();
                        if (typeKind == TypeKind.RECORD) {
                            Cursor anonDecl = fieldType.declaration();
                            CursorKind anonKind = anonDecl.kind();
                            if (anonKind == CursorKind.STRUCT_DECL || anonKind == CursorKind.CLASS_DECL) {
                                anonType = parseAnonymousStruct(pool, anonDecl, category);
                            } else if (anonKind == CursorKind.UNION_DECL) {
                                anonType = parseAnonymousUnion(pool, anonDecl, category);
                            }
                        }
                    }

                    fields.add(new ParsedStructure.FieldInfo(
                        cursor.spelling(),
                        cursor.type().spelling(),
                        isBitField,
                        bitWidth,
                        isAnon,
                        anonType
                    ));
                }
                case PACKED_ATTR -> packed[0] = true;
                default -> {}
            }
            return Cursor.ChildVisitResult.CONTINUE;
        });

        pool.addParsedType(new ParsedStructure(name, fields, packed[0], 0, category));
    }

    private void parseUnion(TypePool pool, Cursor unionCursor, CategoryPath category) {
        String name = unionCursor.spelling();
        if (name == null || name.isEmpty())
            return; // Skip anonymous unions

        List<ParsedStructure.FieldInfo> fields = new ArrayList<>();

        unionCursor.visitChildren((cursor, parent) -> {
            if (cursor.kind() == CursorKind.FIELD_DECL) {
                boolean isBitField = cursor.isBitField();
                int bitWidth = isBitField ? cursor.getBitFieldWidth() : 0;
                boolean isAnon = cursor.isAnonymous();

                ParsedType anonType = null;
                if (isAnon) {
                    Type fieldType = cursor.type().unwrap();
                    TypeKind typeKind = fieldType.kind();
                    if (typeKind == TypeKind.RECORD) {
                        Cursor anonDecl = fieldType.declaration();
                        CursorKind anonKind = anonDecl.kind();
                        if (anonKind == CursorKind.STRUCT_DECL || anonKind == CursorKind.CLASS_DECL) {
                            anonType = parseAnonymousStruct(pool, anonDecl, category);
                        } else if (anonKind == CursorKind.UNION_DECL) {
                            anonType = parseAnonymousUnion(pool, anonDecl, category);
                        }
                    }
                }

                fields.add(new ParsedStructure.FieldInfo(
                    cursor.spelling(),
                    cursor.type().spelling(),
                    isBitField,
                    bitWidth,
                    isAnon,
                    anonType
                ));
            }
            return Cursor.ChildVisitResult.CONTINUE;
        });

        pool.addParsedType(new ParsedUnion(name, fields, category));
    }

    private void parseFunction(TypePool pool, Cursor funcCursor, CategoryPath category) {
        String name = funcCursor.spelling();
        if (name == null || name.isEmpty())
            return;

        Type funcType = funcCursor.type();

        // Extract return type
        String returnTypeName = funcType.resultType().spelling();

        // Extract parameters
        int numArgs = funcType.numArgTypes();
        List<ParsedFunctionType.ParamInfo> params = new ArrayList<>();
        for (int i = 0; i < numArgs; i++) {
            Type argType = funcType.argType(i);
            // Try to get parameter name from the cursor's children
            String paramName = getParamName(funcCursor, i);
            params.add(new ParsedFunctionType.ParamInfo(paramName, argType.spelling()));
        }

        boolean isVariadic = funcType.isFunctionVariadic();

        // Get calling convention
        CallingConvention cc = funcType.callingConvention();
        String ccName = (cc != null) ? cc.getGhidraName() : null;

        pool.addParsedType(new ParsedFunctionType(name, returnTypeName, params,
            isVariadic, ccName, category));
    }

    // ========================================================================
    // Anonymous type helpers
    // ========================================================================

    /**
     * Parse an anonymous struct declaration into a ParsedStructure without
     * registering it in the TypePool (it will be embedded inline).
     */
    private ParsedStructure parseAnonymousStruct(TypePool pool, Cursor structCursor, CategoryPath category) {
        List<ParsedStructure.FieldInfo> fields = new ArrayList<>();
        boolean[] packed = {false};

        structCursor.visitChildren((cursor, parent) -> {
            CursorKind kind = cursor.kind();
            if (kind == CursorKind.FIELD_DECL) {
                boolean isBitField = cursor.isBitField();
                int bitWidth = isBitField ? cursor.getBitFieldWidth() : 0;
                fields.add(new ParsedStructure.FieldInfo(
                    cursor.spelling(), cursor.type().spelling(),
                    isBitField, bitWidth, false, null));
            } else if (kind == CursorKind.PACKED_ATTR) {
                packed[0] = true;
            }
            return Cursor.ChildVisitResult.CONTINUE;
        });

        // Use a synthetic name for the anonymous struct
        String anonName = "anon_struct_" + Integer.toHexString(structCursor.hashCode());
        return new ParsedStructure(anonName, fields, packed[0], 0, category);
    }

    /**
     * Parse an anonymous union declaration into a ParsedUnion without
     * registering it in the TypePool.
     */
    private ParsedUnion parseAnonymousUnion(TypePool pool, Cursor unionCursor, CategoryPath category) {
        List<ParsedStructure.FieldInfo> fields = new ArrayList<>();

        unionCursor.visitChildren((cursor, parent) -> {
            if (cursor.kind() == CursorKind.FIELD_DECL) {
                boolean isBitField = cursor.isBitField();
                int bitWidth = isBitField ? cursor.getBitFieldWidth() : 0;
                fields.add(new ParsedStructure.FieldInfo(
                    cursor.spelling(), cursor.type().spelling(),
                    isBitField, bitWidth, false, null));
            }
            return Cursor.ChildVisitResult.CONTINUE;
        });

        String anonName = "anon_union_" + Integer.toHexString(unionCursor.hashCode());
        return new ParsedUnion(anonName, fields, category);
    }

    // ========================================================================
    // Utility methods
    // ========================================================================

    /**
     * Try to extract the parameter name from a function declaration cursor.
     * Falls back to empty string if the parameter at the given index has no name.
     */
    private String getParamName(Cursor funcCursor, int paramIndex) {
        String[] names = {""};
        int[] currentIndex = {0};

        funcCursor.visitChildren((cursor, parent) -> {
            if (cursor.kind() == CursorKind.PARM_DECL) {
                if (currentIndex[0] == paramIndex) {
                    String spelling = cursor.spelling();
                    names[0] = (spelling != null) ? spelling : "";
                    return Cursor.ChildVisitResult.BREAK;
                }
                currentIndex[0]++;
            }
            return Cursor.ChildVisitResult.CONTINUE;
        });

        return names[0];
    }

    /**
     * Cleans a single option line from a Ghidra .prf profile for use with clang.
     * Handles Ghidra-specific formatting (quoted options with trailing commas,
     * escaped quotes) and filters out Ghidra-specific options that clang doesn't understand.
     *
     * @return the cleaned option string, or null if the line should be skipped
     */
    private static String cleanProfileOption(String raw) {
        String s = raw.trim();
        if (s.isEmpty())
            return null;

        // Strip trailing comma (Ghidra profile format quirk)
        if (s.endsWith(","))
            s = s.substring(0, s.length() - 1).trim();

        // Strip outer double quotes: "-Dfoo=\"bar\"" -> -Dfoo=\"bar\"
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
            s = s.substring(1, s.length() - 1);

        // Unescape inner quotes: \" -> "
        s = s.replace("\\\"", "\"");

        if (s.isEmpty())
            return null;

        // Filter out Ghidra-specific options that clang doesn't understand
        // -v0, -v1, -v2 etc. are Ghidra C parser verbosity flags
        if (s.matches("-v\\d+"))
            return null;

        return s;
    }
}
