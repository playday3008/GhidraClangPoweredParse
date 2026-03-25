package playday3008.gcpp.processing;

import ghidra.program.model.data.*;
import java.util.List;
import java.util.stream.Collectors;

public class ParsedUnion extends ParsedType {
    // Uses same FieldInfo from ParsedStructure
    private final List<ParsedStructure.FieldInfo> fields;

    public ParsedUnion(String name, List<ParsedStructure.FieldInfo> fields, CategoryPath categoryPath) {
        super(name, categoryPath);
        this.fields = List.copyOf(fields);
    }

    @Override
    public DataType createDataType(TypePool pool) {
        var union = new UnionDataType(getCategoryPath(), getName(), pool.getDataTypeManager());
        union.setPackingEnabled(true);

        for (var field : fields) {
            if (field.isBitField()) {
                DataType fieldType = pool.getType(field.typeName());
                if (fieldType != null) {
                    try {
                        union.addBitField(fieldType, field.bitFieldWidth(), field.name(), null);
                    } catch (InvalidDataTypeException e) {
                        union.add(fieldType, field.name(), null);
                    }
                }
            } else if (field.isAnonymous() && field.anonymousType() != null) {
                DataType anonDt = field.anonymousType().createDataType(pool);
                if (anonDt != null) {
                    union.add(anonDt, "", null);
                }
            } else {
                DataType fieldType = pool.getType(field.typeName());
                if (fieldType != null) {
                    union.add(fieldType, field.name(), null);
                }
            }
        }

        return union;
    }

    @Override
    public List<String> getDependencies() {
        return fields.stream()
            .filter(f -> f.typeName() != null && !f.isAnonymous())
            .map(ParsedStructure.FieldInfo::typeName)
            .collect(Collectors.toList());
    }
}
