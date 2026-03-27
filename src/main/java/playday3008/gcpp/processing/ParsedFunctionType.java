package playday3008.gcpp.processing;

import ghidra.program.model.data.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ParsedFunctionType extends ParsedType {
    private static final Logger LOGGER = LogManager.getLogger();

    public record ParamInfo(String name, String typeName) {}

    private final String returnTypeName;
    private final List<ParamInfo> params;
    private final boolean isVariadic;
    private final String callingConvention;

    public ParsedFunctionType(String name, String returnTypeName, List<ParamInfo> params,
                              boolean isVariadic, String callingConvention,
                              CategoryPath categoryPath) {
        super(name, categoryPath);
        this.returnTypeName = returnTypeName;
        this.params = List.copyOf(params);
        this.isVariadic = isVariadic;
        this.callingConvention = callingConvention;
    }

    @Override
    public DataType createDataType(TypePool pool) {
        CategoryPath funcCategory = new CategoryPath(getCategoryPath(), "functions");
        var funcDef = new FunctionDefinitionDataType(funcCategory, getName(), pool.getDataTypeManager());

        DataType retType = pool.getType(returnTypeName);
        if (retType != null) {
            funcDef.setReturnType(retType);
        }

        List<ParameterDefinition> paramDefs = new ArrayList<>();
        for (var param : params) {
            DataType paramType = pool.getType(param.typeName());
            if (paramType != null) {
                paramDefs.add(new ParameterDefinitionImpl(
                    param.name() != null && !param.name().isEmpty() ? param.name() : null,
                    paramType, null));
            }
        }
        funcDef.setArguments(paramDefs.toArray(new ParameterDefinition[0]));
        funcDef.setVarArgs(isVariadic);

        if (callingConvention != null) {
            try {
                funcDef.setCallingConvention(callingConvention);
            } catch (Exception e) {
                LOGGER.warn("Invalid calling convention '{}' for function '{}': {}", callingConvention, getName(), e.getMessage());
            }
        }

        return funcDef;
    }

    @Override
    public List<String> getDependencies() {
        var deps = new ArrayList<String>();
        deps.add(returnTypeName);
        deps.addAll(params.stream().map(ParamInfo::typeName).collect(Collectors.toList()));
        return deps;
    }
}
