package playday3008.gcpp.clang.error;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

public enum ParseErrorCode {
    SUCCESS(0),
    FAILURE(1),
    CRASHED(2),
    INVALID_ARGUMENTS(3),
    AST_READ_ERROR(4);

    private static final Map<Integer, ParseErrorCode> BY_CODE = new HashMap<>();

    private final int code;

    ParseErrorCode(int code) {
        this.code = code;
    }

    public int getCode() {
        return this.code;
    }

    public static ParseErrorCode fromInteger(int code) {
        if (!BY_CODE.containsKey(code))
            throw new RuntimeException("Unknown parse error " + code);

        return BY_CODE.get(code);
    }

    static {
        EnumSet.allOf(ParseErrorCode.class).forEach(e -> BY_CODE.put(e.code, e));
    }
}
