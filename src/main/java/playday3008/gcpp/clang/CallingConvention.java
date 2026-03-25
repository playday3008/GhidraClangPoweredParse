package playday3008.gcpp.clang;

import java.util.HashMap;
import java.util.Map;

public enum CallingConvention {
    DEFAULT(0, "__cdecl"),
    C(1, "__cdecl"),
    X86_STDCALL(2, "__stdcall"),
    X86_FASTCALL(3, "__fastcall"),
    X86_THISCALL(4, "__thiscall"),
    X86_PASCAL(5, "__pascal"),
    AAPCS(6, "__cdecl"),
    AAPCS_VFP(7, "__cdecl"),
    X86_REGCALL(8, "__regcall"),
    INTEL_OCL_BICC(9, "__cdecl"),
    WIN64(10, "__fastcall"),
    X86_64_SYSV(11, "__cdecl"),
    X86_VECTORCALL(12, "__vectorcall"),
    SWIFT(13, "__cdecl"),
    PRESERVE_MOST(14, "__cdecl"),
    PRESERVE_ALL(15, "__cdecl"),
    AARCH64_VECTORCALL(16, "__cdecl"),
    SWIFT_ASYNC(17, "__cdecl"),
    AARCH64_SVE_PCS(18, "__cdecl"),
    INVALID(100, null),
    UNEXPOSED(200, null);

    private final int value;
    private final String ghidraName;
    private static final Map<Integer, CallingConvention> BY_VALUE = new HashMap<>();

    static {
        for (var v : values()) BY_VALUE.put(v.value, v);
    }

    CallingConvention(int value, String ghidraName) {
        this.value = value;
        this.ghidraName = ghidraName;
    }

    public int getValue() {
        return value;
    }

    public String getGhidraName() {
        return ghidraName;
    }

    public static CallingConvention fromInteger(int value) {
        return BY_VALUE.get(value);
    }
}
