package playday3008.gcpp.processing;

import ghidra.program.model.data.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.stream.Collectors;

public class ParsedStructure extends ParsedType {
    private static final Logger LOGGER = LogManager.getLogger();

    public record FieldInfo(
        String name,
        String typeName,
        boolean isBitField,
        int bitFieldWidth,
        boolean isAnonymous,
        ParsedType anonymousType
    ) {}

    private final List<FieldInfo> fields;
    private final boolean isPacked;
    private final int explicitPackValue;

    public ParsedStructure(String name, List<FieldInfo> fields, boolean isPacked,
                           int explicitPackValue, CategoryPath categoryPath) {
        super(name, categoryPath);
        this.fields = List.copyOf(fields);
        this.isPacked = isPacked;
        this.explicitPackValue = explicitPackValue;
    }

    @Override
    public DataType createDataType(TypePool pool) {
        var struct = new StructureDataType(getCategoryPath(), getName(), 0, pool.getDataTypeManager());
        struct.setPackingEnabled(true);
        if (isPacked) {
            struct.setExplicitPackingValue(1);
        } else if (explicitPackValue > 0) {
            struct.setExplicitPackingValue(explicitPackValue);
        }

        for (var field : fields) {
            if (field.isBitField()) {
                DataType fieldType = pool.getType(field.typeName());
                if (fieldType != null) {
                    try {
                        struct.addBitField(fieldType, field.bitFieldWidth(), field.name(), null);
                    } catch (InvalidDataTypeException e) {
                        LOGGER.warn("Bit-field failed for '{}.{}' (width={}), falling back to regular field: {}",
                            getName(), field.name(), field.bitFieldWidth(), e.getMessage());
                        struct.add(fieldType, field.name(), null);
                    }
                }
            } else if (field.isAnonymous() && field.anonymousType() != null) {
                DataType anonDt = field.anonymousType().createDataType(pool);
                if (anonDt != null) {
                    struct.add(anonDt, "", null);
                }
            } else {
                DataType fieldType = pool.getType(field.typeName());
                if (fieldType != null) {
                    struct.add(fieldType, field.name(), null);
                }
            }
        }

        return struct;
    }

    @Override
    public List<String> getDependencies() {
        return fields.stream()
            .filter(f -> f.typeName() != null && !f.isAnonymous())
            .map(FieldInfo::typeName)
            .collect(Collectors.toList());
    }

    public List<FieldInfo> getFields() { return fields; }
}
