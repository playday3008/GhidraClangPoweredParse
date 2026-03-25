package playday3008.gcpp.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps Ghidra LanguageID / CompilerSpecID pairs to clang command-line arguments.
 *
 * Ghidra format: {@code <Processor>:<Endian>:<Size>:<Variant> / <Compiler>}
 * Examples: {@code x86:LE:64:default / gcc}, {@code x86:LE:64:default / windows}
 */
public class ArchitectureMapping
{
    // Processor arch portion of the clang target triple, keyed by "processor:endian:size"
    private static final Map<String, String> ARCH_MAP = Map.ofEntries(
        Map.entry("x86:LE:64",        "x86_64"),
        Map.entry("x86:LE:32",        "i386"),
        Map.entry("x86:LE:16",        "i386"),
        Map.entry("ARM:LE:32",        "arm"),
        Map.entry("ARM:BE:32",        "armeb"),
        Map.entry("AARCH64:LE:64",    "aarch64"),
        Map.entry("AARCH64:BE:64",    "aarch64_be"),
        Map.entry("MIPS:BE:32",       "mips"),
        Map.entry("MIPS:LE:32",       "mipsel"),
        Map.entry("MIPS:BE:64",       "mips64"),
        Map.entry("MIPS:LE:64",       "mips64el"),
        Map.entry("PowerPC:BE:32",    "powerpc"),
        Map.entry("PowerPC:BE:64",    "powerpc64"),
        Map.entry("PowerPC:LE:64",    "powerpc64le"),
        Map.entry("sparc:BE:32",      "sparc"),
        Map.entry("sparc:BE:64",      "sparc64"),
        Map.entry("RISCV:LE:32",      "riscv32"),
        Map.entry("RISCV:LE:64",      "riscv64"),
        Map.entry("68000:BE:32",      "m68k"),
        Map.entry("AVR8:LE:16",       "avr"),
        Map.entry("Loongarch:LE:64",  "loongarch64"),
        Map.entry("Loongarch:LE:32",  "loongarch32"),
        Map.entry("S-390:BE:64",      "s390x"),
        Map.entry("S-390:BE:32",      "s390")
    );

    /**
     * Builds the full list of clang arguments for a given Ghidra language/compiler pair.
     * Includes --target triple and compiler-specific flags.
     *
     * @param languageId   Ghidra LanguageID (e.g. "x86:LE:64:default"), may be null
     * @param compilerSpec Ghidra CompilerSpecID (e.g. "gcc", "windows"), may be null
     * @return list of clang arguments, possibly empty
     */
    public static List<String> getClangArgs(String languageId, String compilerSpec)
    {
        List<String> args = new ArrayList<>();

        String arch = getArch(languageId);
        if (arch == null)
            return args;

        boolean isMsvc = isMsvcCompiler(compilerSpec);
        boolean isMacOS = isMacOSCompiler(compilerSpec);

        // Build the --target triple: <arch>-<vendor>-<os>[-<env>]
        String triple;
        if (isMsvc)
            triple = arch + "-pc-windows-msvc";
        else if (isMacOS)
            triple = arch + "-apple-darwin";
        else
            triple = arch + "-unknown-linux-gnu";

        args.add("--target=" + triple);

        // Compiler-specific flags
        if (isMsvc)
        {
            args.add("-fms-extensions");
            args.add("-fms-compatibility");
            args.add("-fdeclspec");
        }
        else
        {
            args.add("-fgnuc-version=12.0.0");
        }

        return args;
    }

    /**
     * Returns just the target triple, or null if unmapped.
     */
    public static String getTargetTriple(String languageId, String compilerSpec)
    {
        List<String> args = getClangArgs(languageId, compilerSpec);
        for (String arg : args)
        {
            if (arg.startsWith("--target="))
                return arg.substring("--target=".length());
        }
        return null;
    }

    private static String getArch(String languageId)
    {
        if (languageId == null || languageId.isEmpty())
            return null;
        return ARCH_MAP.get(getLanguagePrefix(languageId));
    }

    private static boolean isMsvcCompiler(String compilerSpec)
    {
        if (compilerSpec == null)
            return false;
        String lower = compilerSpec.toLowerCase();
        return lower.equals("windows") || lower.equals("visual studio")
            || lower.contains("msvc") || lower.contains("borland")
            || lower.contains("delphi");
    }

    private static boolean isMacOSCompiler(String compilerSpec)
    {
        if (compilerSpec == null)
            return false;
        String lower = compilerSpec.toLowerCase();
        return lower.contains("mac") || lower.contains("clang") || lower.contains("swift");
    }

    /**
     * Extracts "processor:endianness:bitwidth" from a full LanguageID string.
     * e.g. "x86:LE:64:default" -> "x86:LE:64"
     */
    private static String getLanguagePrefix(String languageIdString)
    {
        String[] parts = languageIdString.split(":");
        if (parts.length >= 3)
            return parts[0] + ":" + parts[1] + ":" + parts[2];
        return languageIdString;
    }
}
