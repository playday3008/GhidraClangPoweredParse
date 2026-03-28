package playday3008.gcpp.processing;

import ghidra.program.model.data.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a C++ vtable as a struct of typed function pointers.
 * <p>
 * Each virtual method in the class becomes a function pointer entry in the vtable struct.
 * The function signatures include an explicit {@code this} pointer as the first parameter,
 * matching the actual calling convention at the binary level.
 */
public class ParsedVtable extends ParsedType {
    private static final Logger LOGGER = LogManager.getLogger();

    public record VtableEntry(
        String name,
        String returnTypeName,
        List<ParsedFunctionType.ParamInfo> params,
        boolean isVariadic,
        String callingConvention
    ) {}

    private final List<VtableEntry> entries;
    private final String className;

    public ParsedVtable(String name, List<VtableEntry> entries, String className,
                        CategoryPath categoryPath) {
        super(name, categoryPath);
        this.entries = List.copyOf(entries);
        this.className = className;
    }

    @Override
    public DataType createDataType(TypePool pool) {
        var vtable = new StructureDataType(getCategoryPath(), getName(), 0, pool.getDataTypeManager());
        vtable.setPackingEnabled(true);

        for (var entry : entries) {
            var funcDef = new FunctionDefinitionDataType(
                new CategoryPath(getCategoryPath(), "functions"),
                className + "::" + entry.name(),
                pool.getDataTypeManager()
            );

            DataType retType = pool.getType(entry.returnTypeName());
            if (retType != null) {
                funcDef.setReturnType(retType);
            }

            List<ParameterDefinition> paramDefs = new ArrayList<>();
            for (var param : entry.params()) {
                DataType paramType = pool.getType(param.typeName());
                if (paramType != null) {
                    paramDefs.add(new ParameterDefinitionImpl(
                        param.name() != null && !param.name().isEmpty() ? param.name() : null,
                        paramType, null));
                }
            }
            funcDef.setArguments(paramDefs.toArray(new ParameterDefinition[0]));
            funcDef.setVarArgs(entry.isVariadic());

            if (entry.callingConvention() != null) {
                try {
                    funcDef.setCallingConvention(entry.callingConvention());
                } catch (Exception e) {
                    LOGGER.warn("Invalid calling convention '{}' for vtable entry '{}::{}': {}",
                        entry.callingConvention(), className, entry.name(), e.getMessage());
                }
            }

            var funcPtr = new PointerDataType(funcDef, pool.getDataTypeManager());
            vtable.add(funcPtr, entry.name(), null);
        }

        return vtable;
    }

    /**
     * Returns an empty dependency list. Vtable entries are all function pointers
     * (fixed pointer size regardless of signature), so the vtable struct can always
     * be created immediately. Individual function signatures gracefully handle
     * missing param/return types in {@link #createDataType}.
     */
    @Override
    public List<String> getDependencies() {
        return List.of();
    }
}
