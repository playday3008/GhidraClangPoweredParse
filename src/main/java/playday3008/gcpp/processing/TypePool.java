package playday3008.gcpp.processing;

import ghidra.program.model.data.*;
import ghidra.util.data.DataTypeParser;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TypePool {
    private static final Logger LOGGER = LogManager.getLogger();
    private final DataTypeManager[] openArchives;

    private final Map<String, ParsedType> parsedTypes = new HashMap<>();
    private final StandAloneDataTypeManager dtm;
    private final DataTypeParser typeParser;

    // Pattern to match trailing array dimensions, e.g. "[10]", "[0]"
    private static final Pattern ARRAY_SUFFIX = Pattern.compile("^(.+)\\[(\\d+)]$");

    /**
     * @param openArchives additional DTMs to search for existing types (may be null)
     */
    public TypePool(DataTypeManager[] openArchives) {
        this.openArchives = openArchives;

        this.dtm = new StandAloneDataTypeManager(CategoryPath.ROOT.getName());
        // Pass null for DataTypeManagerService to avoid interactive per-type chooser dialogs.
        // Open archives are searched explicitly in getType() instead.
        this.typeParser = new DataTypeParser(this.dtm, this.dtm, null, DataTypeParser.AllowedDataTypes.FIXED_LENGTH);
    }

    public ResolutionResult resolve() {
        LOGGER.debug("Starting type resolution for {} parsed types", parsedTypes.size());
        int transaction = this.dtm.startTransaction("Process clang types");

        // Pre-register forward declarations for all structs and unions.
        // This allows pointer-to-struct/union dependencies to resolve before
        // the actual type is fully defined — critical for self-referential
        // types (e.g. linked list nodes) and cross-referential struct pointers.
        int forwardDecls = 0;
        for (ParsedType pt : this.parsedTypes.values()) {
            if (pt instanceof ParsedStructure) {
                this.dtm.addDataType(
                    new StructureDataType(pt.getCategoryPath(), pt.getName(), 0, dtm),
                    DataTypeConflictHandler.REPLACE_HANDLER);
                forwardDecls++;
            } else if (pt instanceof ParsedUnion) {
                this.dtm.addDataType(
                    new UnionDataType(pt.getCategoryPath(), pt.getName(), dtm),
                    DataTypeConflictHandler.REPLACE_HANDLER);
                forwardDecls++;
            }
        }
        LOGGER.debug("Registered {} forward declarations", forwardDecls);

        Set<ParsedType> outstandingParsedTypes = new HashSet<>(this.parsedTypes.values());

        // Iteratively create data types as their dependencies are fulfilled.
        // When no more progress can be made, skip unresolvable types rather than
        // failing entirely — system headers commonly reference types from unparsed
        // headers, function pointers, etc. that can't be resolved.
        int iteration = 0;
        while (!outstandingParsedTypes.isEmpty()) {
            boolean hasResolved = false;
            int resolvedThisPass = 0;

            for (var it = outstandingParsedTypes.iterator(); it.hasNext(); ) {
                var parsedType = it.next();

                if (this.checkDependenciesFulfilled(parsedType)) {
                    try {
                        var dt = parsedType.createDataType(this);
                        if (dt != null) {
                            this.dtm.addDataType(dt, DataTypeConflictHandler.REPLACE_HANDLER);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to create type '{}': {}", parsedType.getName(), e.getMessage());
                    }
                    it.remove();
                    hasResolved = true;
                    resolvedThisPass++;
                }
            }

            iteration++;
            LOGGER.debug("Resolution pass {}: resolved {} types, {} remaining",
                iteration, resolvedThisPass, outstandingParsedTypes.size());

            // No more progress — remaining types have unresolvable dependencies
            if (!hasResolved)
                break;
        }

        this.dtm.endTransaction(transaction, true);

        // Collect what we resolved
        Set<String> unresolvedDependencies = Set.of();
        if (!outstandingParsedTypes.isEmpty()) {
            unresolvedDependencies = outstandingParsedTypes.stream()
                .flatMap(parsedType -> this.getUnfulfilledDependencies(parsedType).stream())
                .collect(Collectors.toSet());
            LOGGER.warn("{} types could not be resolved due to unfulfilled dependencies: {}",
                outstandingParsedTypes.size(),
                outstandingParsedTypes.stream().map(ParsedType::getName).collect(Collectors.joining(", ")));
        }

        List<DataType> allTypes = new ArrayList<>();
        this.dtm.getAllDataTypes(allTypes);
        LOGGER.debug("Type resolution complete: {} types resolved in {} passes", allTypes.size(), iteration);
        return new ResolutionResult(List.copyOf(allTypes), Set.copyOf(unresolvedDependencies));
    }

    public void addParsedType(ParsedType type) {
        this.parsedTypes.put(type.getName(), type);
    }

    public void clearParsedTypes() {
        this.parsedTypes.clear();
    }

    public DataTypeManager getDataTypeManager() {
        return this.dtm;
    }

    /**
     * Closes the internal StandAloneDataTypeManager, releasing resources.
     * Call this when the TypePool is no longer needed.
     */
    public void close() {
        this.dtm.close();
    }

    public DataType getType(String name) {
        // Try direct resolution first
        DataType dt = resolveType(name);
        if (dt != null)
            return dt;

        // Normalize: strip CV qualifiers and elaborated type specifiers.
        // Clang may spell types as "const struct _FOO *" but we register them as "_FOO".
        // Ghidra's DataTypeParser doesn't understand const/volatile qualifiers.
        String normalized = normalizeTypeName(name);
        if (normalized != null) {
            dt = resolveType(normalized);
            if (dt != null)
                return dt;

            // Try pointer/array/function-pointer handling on the normalized name too
            dt = resolveComposite(normalized);
            if (dt != null)
                return dt;
        }

        // Try pointer, array, and function pointer handling on the original name
        dt = resolveComposite(name);
        if (dt != null)
            return dt;

        return null;
    }

    /**
     * Handles pointer types (trailing *), array types (trailing [N]),
     * and function pointers (contains (*) or (^)).
     */
    private DataType resolveComposite(String name) {
        // Handle pointer types: if name ends with *, strip it, resolve base, wrap in PointerDataType
        if (name.endsWith("*")) {
            String baseName = name.substring(0, name.length() - 1).trim();
            DataType baseType = getType(baseName);
            if (baseType != null) {
                return new PointerDataType(baseType, this.dtm);
            }
            // Unknown base — return a generic void pointer
            return new PointerDataType(this.dtm);
        }

        // Handle array types: if name ends with [N], extract N, strip it, resolve element type
        Matcher arrayMatcher = ARRAY_SUFFIX.matcher(name.trim());
        if (arrayMatcher.matches()) {
            String elementName = arrayMatcher.group(1).trim();
            int count = Integer.parseInt(arrayMatcher.group(2));
            DataType elementType = getType(elementName);
            if (elementType != null) {
                return new ArrayDataType(elementType, count, elementType.getLength(), this.dtm);
            }
        }

        // Handle function pointers: if name contains (*) or (^), look up in parsed types
        // first, then fall back to generic PointerDataType
        if (name.contains("(*)") || name.contains("(^)")) {
            // Check if we have a parsed function type that matches
            ParsedType parsed = parsedTypes.get(name);
            if (parsed instanceof ParsedFunctionType) {
                DataType funcDt = parsed.createDataType(this);
                if (funcDt != null) {
                    return new PointerDataType(funcDt, this.dtm);
                }
            }
            return new PointerDataType(this.dtm);
        }

        return null;
    }

    private DataType resolveType(String name) {
        // Check our internal StandAloneDataTypeManager (built-in + already resolved types)
        try {
            DataType dt = typeParser.parse(name);
            if (dt != null)
                return dt;
        } catch (Exception ignored) {}

        // Then check the open archives the user chose to use
        if (openArchives != null) {
            for (DataTypeManager archive : openArchives) {
                DataType dt = findTypeByName(archive, name);
                if (dt != null) {
                    // Copy it into our DTM so it's available for future resolution
                    return dtm.addDataType(dt, DataTypeConflictHandler.REPLACE_HANDLER);
                }
            }
        }

        return null;
    }

    private static final String[] CV_QUALIFIERS = {"const ", "volatile ", "restrict ", "__unaligned "};
    private static final String[] ELABORATED_PREFIXES = {"struct ", "union ", "enum ", "class "};

    /**
     * Strips C/C++ CV qualifiers and elaborated type specifiers from a type name.
     * Handles "const struct _FOO *", "volatile DWORD", "const volatile int *", etc.
     * Ghidra data types don't model const/volatile, so these are safe to strip.
     * Returns the normalized name, or null if no changes were made.
     */
    private static String normalizeTypeName(String name) {
        String s = name;
        boolean changed = false;

        // Strip leading CV qualifiers (may be stacked: "const volatile struct _FOO")
        boolean stripped;
        do {
            stripped = false;
            for (String q : CV_QUALIFIERS) {
                if (s.startsWith(q)) {
                    s = s.substring(q.length());
                    changed = true;
                    stripped = true;
                    break;
                }
            }
        } while (stripped);

        // Strip elaborated type specifiers (struct/union/enum/class prefix)
        for (String p : ELABORATED_PREFIXES) {
            if (s.startsWith(p)) {
                s = s.substring(p.length());
                changed = true;
                break;
            }
        }

        return changed ? s : null;
    }

    private DataType findTypeByName(DataTypeManager mgr, String name) {
        // Try direct lookup first (handles fully qualified names)
        List<DataType> results = new ArrayList<>();
        mgr.findDataTypes(name, results);
        if (!results.isEmpty())
            return results.get(0);
        return null;
    }

    private boolean hasType(String name) {
        return this.getType(name) != null;
    }

    private boolean checkDependenciesFulfilled(ParsedType type) {
        for (var dependency : type.getDependencies()) {
            if (!this.hasType(dependency))
                return false;
        }
        return true;
    }

    private List<String> getUnfulfilledDependencies(ParsedType type) {
        return type.getDependencies().stream().filter(s -> !this.hasType(s)).toList();
    }

    public static class ResolutionResult {
        private final List<DataType> dataTypes;
        private final Set<String> unresolvedDependencies;

        ResolutionResult(List<DataType> dataTypes, Set<String> unresolvedDependencies) {
            this.dataTypes = dataTypes;
            this.unresolvedDependencies = unresolvedDependencies;
        }

        public List<DataType> getDataTypes() {
            return this.dataTypes;
        }

        public Set<String> getUnresolvedDependencies() {
            return this.unresolvedDependencies;
        }
    }
}
