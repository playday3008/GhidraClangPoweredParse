package playday3008.gcpp.processing;

import ghidra.program.model.data.CategoryPath;
import ghidra.program.model.data.DataType;
import java.util.List;

public abstract class ParsedType {
    private final String name;
    private final CategoryPath categoryPath;

    protected ParsedType(String name, CategoryPath categoryPath) {
        this.name = name;
        this.categoryPath = categoryPath;
    }

    public String getName() { return name; }
    public CategoryPath getCategoryPath() { return categoryPath; }
    public abstract DataType createDataType(TypePool pool);
    public abstract List<String> getDependencies();
}
