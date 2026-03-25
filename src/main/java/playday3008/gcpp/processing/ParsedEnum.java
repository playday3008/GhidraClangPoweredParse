package playday3008.gcpp.processing;

import ghidra.program.model.data.*;
import java.util.*;

public class ParsedEnum extends ParsedType {
    private final int size;
    private final LinkedHashMap<String, Long> values;

    public ParsedEnum(String name, int size, LinkedHashMap<String, Long> values, CategoryPath categoryPath) {
        super(name, categoryPath);
        this.size = size;
        this.values = new LinkedHashMap<>(values);
    }

    @Override
    public DataType createDataType(TypePool pool) {
        var enumDt = new EnumDataType(getCategoryPath(), getName(), size, pool.getDataTypeManager());
        values.forEach((name, value) -> enumDt.add(name, value));
        return enumDt;
    }

    @Override
    public List<String> getDependencies() {
        return List.of();
    }
}
