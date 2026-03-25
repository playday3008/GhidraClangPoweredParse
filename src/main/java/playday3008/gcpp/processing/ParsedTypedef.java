package playday3008.gcpp.processing;

import ghidra.program.model.data.*;
import java.util.List;

public class ParsedTypedef extends ParsedType {
    private final String typeName;

    public ParsedTypedef(String name, String typeName, CategoryPath categoryPath) {
        super(name, categoryPath);
        this.typeName = typeName;
    }

    @Override
    public DataType createDataType(TypePool pool) {
        DataType dt = pool.getType(typeName);
        if (dt != null) {
            return new TypedefDataType(getCategoryPath(), getName(), dt, pool.getDataTypeManager());
        }
        return null;
    }

    @Override
    public List<String> getDependencies() {
        return List.of(typeName);
    }
}
