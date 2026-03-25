package playday3008.gcpp.clang.error;

public class ParseException extends Exception {

    public ParseException(ParseErrorCode code) {
        super(getMessage(code));
    }

    public ParseException(int code) {
        this(ParseErrorCode.fromInteger(code));
    }

    private static String getMessage(ParseErrorCode code) {
        return switch (code) {
            case SUCCESS -> "No libclang error occurred";
            case FAILURE -> "A generic libclang error occurred";
            case CRASHED -> "libclang crashed while performing the requested operation";
            case INVALID_ARGUMENTS -> "The function detected that the arguments violate the function contract";
            case AST_READ_ERROR -> "An AST deserialization error has occurred";
        };
    }
}
