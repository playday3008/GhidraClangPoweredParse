package playday3008.gcpp.processing;

import ghidra.program.model.lang.*;
import ghidra.program.util.DefaultLanguageService;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps Ghidra LanguageID / CompilerSpecID pairs to clang command-line arguments.
 * <p>
 * Uses Ghidra's {@link DefaultLanguageService} to resolve {@link LanguageDescription}
 * from a {@link LanguageID}, falling back to string parsing if the service is unavailable.
 */
public class ArchitectureMapping
{
    /**
     * Maps Ghidra processor / endianness / address-size triples to clang
     * target architecture names used in {@code --target} triples.
     * <p>
     * Each constant corresponds to one Ghidra {@link Processor} +
     * {@link Endian} + size combination that this plugin knows how to target.
     */
    public enum ClangArch
    {
        X86_16      ("x86",        Endian.LITTLE, 16, "i386"),
        X86_32      ("x86",        Endian.LITTLE, 32, "i386"),
        X86_64      ("x86",        Endian.LITTLE, 64, "x86_64"),
        ARM_LE      ("ARM",        Endian.LITTLE, 32, "arm"),
        ARM_BE      ("ARM",        Endian.BIG,    32, "armeb"),
        AARCH64_LE  ("AARCH64",    Endian.LITTLE, 64, "aarch64"),
        AARCH64_BE  ("AARCH64",    Endian.BIG,    64, "aarch64_be"),
        MIPS_BE_32  ("MIPS",       Endian.BIG,    32, "mips"),
        MIPS_LE_32  ("MIPS",       Endian.LITTLE, 32, "mipsel"),
        MIPS_BE_64  ("MIPS",       Endian.BIG,    64, "mips64"),
        MIPS_LE_64  ("MIPS",       Endian.LITTLE, 64, "mips64el"),
        PPC_BE_32   ("PowerPC",    Endian.BIG,    32, "powerpc"),
        PPC_BE_64   ("PowerPC",    Endian.BIG,    64, "powerpc64"),
        PPC_LE_64   ("PowerPC",    Endian.LITTLE, 64, "powerpc64le"),
        SPARC_32    ("sparc",      Endian.BIG,    32, "sparc"),
        SPARC_64    ("sparc",      Endian.BIG,    64, "sparc64"),
        RISCV_32    ("RISCV",      Endian.LITTLE, 32, "riscv32"),
        RISCV_64    ("RISCV",      Endian.LITTLE, 64, "riscv64"),
        M68K        ("68000",      Endian.BIG,    32, "m68k"),
        AVR         ("AVR8",       Endian.LITTLE, 16, "avr"),
        LOONGARCH64 ("Loongarch",  Endian.LITTLE, 64, "loongarch64"),
        LOONGARCH32 ("Loongarch",  Endian.LITTLE, 32, "loongarch32"),
        S390X       ("S-390",      Endian.BIG,    64, "s390x"),
        S390        ("S-390",      Endian.BIG,    32, "s390");

        private final String processor;
        private final Endian endian;
        private final int size;
        private final String clangArch;

        ClangArch(String processor, Endian endian, int size, String clangArch)
        {
            this.processor = processor;
            this.endian = endian;
            this.size = size;
            this.clangArch = clangArch;
        }

        /** Clang target architecture name (e.g. {@code "x86_64"}, {@code "aarch64"}). */
        public String clangArch()  { return clangArch; }
        /** Ghidra processor name as it appears in the LanguageID. */
        public String processor()  { return processor; }
        /** Expected endianness. */
        public Endian endian()     { return endian; }
        /** Address size in bits (16, 32, 64). */
        public int size()          { return size; }

        /**
         * Finds the matching {@code ClangArch} for a Ghidra {@link LanguageDescription}.
         *
         * @return the matching constant, or {@code null} if no mapping exists
         */
        public static ClangArch find(LanguageDescription desc)
        {
            return find(desc.getProcessor().toString(), desc.getEndian(), desc.getSize());
        }

        /**
         * Finds the matching {@code ClangArch} for a processor name, endianness, and address size.
         *
         * @return the matching constant, or {@code null} if no mapping exists
         */
        public static ClangArch find(String processor, Endian endian, int size)
        {
            for (ClangArch arch : values())
                if (arch.processor.equals(processor) && arch.endian == endian && arch.size == size)
                    return arch;
            return null;
        }
    }

    /**
     * Determines the target environment (OS, vendor, ABI) from a Ghidra
     * {@link CompilerSpecID} string, similar to how Ghidra's LLDB debugger agent
     * uses separate {@code compiler_map} / {@code x86_compiler_map} dicts.
     * <p>
     * Each constant defines the vendor and OS-ABI portions of the clang target
     * triple, plus any extra compiler-compatibility flags needed for parsing.
     *
     * @see <a href="https://github.com/NationalSecurityAgency/ghidra/blob/master/Ghidra/Debug/Debugger-agent-lldb/src/main/py/src/ghidralldb/arch.py">
     *      Ghidra LLDB agent arch.py</a>
     */
    public enum TargetEnvironment
    {
        LINUX  ("unknown", "linux-gnu",    List.of("-fgnuc-version=12.0.0")),
        WINDOWS("pc",      "windows-msvc", List.of(
            "-fms-extensions", "-fms-compatibility", "-fdeclspec",
            // Match MSVC 2022 17.10 — enables proper typedef redefinition tolerance
            // and MSVC-specific parsing behaviors (e.g. enum forward declarations)
            "-fms-compatibility-version=19.40",
            "-fdelayed-template-parsing"
        )),
        MACOS  ("apple",   "darwin",       List.of("-fgnuc-version=12.0.0"));

        private final String vendor;
        private final String osSuffix;
        private final List<String> extraArgs;

        TargetEnvironment(String vendor, String osSuffix, List<String> extraArgs)
        {
            this.vendor = vendor;
            this.osSuffix = osSuffix;
            this.extraArgs = extraArgs;
        }

        /** Builds the full clang target triple for the given architecture. */
        public String buildTriple(String clangArch)
        {
            return clangArch + "-" + vendor + "-" + osSuffix;
        }

        /** Additional clang flags needed for this environment's ABI/extensions. */
        public List<String> extraArgs() { return extraArgs; }

        /**
         * Resolves a Ghidra {@link CompilerSpecID} string to a target environment.
         * <p>
         * Mirrors the {@code compiler_map} pattern from Ghidra's LLDB agent:
         * {@code "windows"} and MSVC-family specs map to {@link #WINDOWS},
         * macOS-related specs map to {@link #MACOS}, everything else defaults
         * to {@link #LINUX}.
         */
        public static TargetEnvironment fromCompilerSpec(String compilerSpecId)
        {
            if (compilerSpecId == null)
                return LINUX;
            String lower = compilerSpecId.toLowerCase();
            if (lower.equals("windows") || lower.equals("visual studio")
                || lower.contains("msvc") || lower.contains("borland")
                || lower.contains("delphi"))
                return WINDOWS;
            if (lower.contains("mac") || lower.contains("clang") || lower.contains("swift"))
                return MACOS;
            return LINUX;
        }
    }

    /**
     * Builds the full list of clang arguments for a given Ghidra language/compiler pair.
     * Includes {@code --target} triple and compiler-specific flags.
     *
     * @param languageId   Ghidra LanguageID string (e.g. {@code "x86:LE:64:default"}), may be null
     * @param compilerSpec Ghidra CompilerSpecID string (e.g. {@code "gcc"}, {@code "windows"}), may be null
     * @return list of clang arguments, possibly empty if the architecture is unmapped
     */
    public static List<String> getClangArgs(String languageId, String compilerSpec)
    {
        ClangArch arch = resolveArch(languageId);
        if (arch == null)
            return List.of();

        TargetEnvironment env = TargetEnvironment.fromCompilerSpec(compilerSpec);

        List<String> args = new ArrayList<>();
        args.add("--target=" + env.buildTriple(arch.clangArch()));
        args.addAll(env.extraArgs());
        return args;
    }

    /**
     * Returns just the target triple, or {@code null} if unmapped.
     */
    public static String getTargetTriple(String languageId, String compilerSpec)
    {
        ClangArch arch = resolveArch(languageId);
        if (arch == null)
            return null;
        return TargetEnvironment.fromCompilerSpec(compilerSpec).buildTriple(arch.clangArch());
    }

    /**
     * Resolves a Ghidra LanguageID string to a {@link ClangArch}.
     * <p>
     * Tries {@link DefaultLanguageService} first to get a proper
     * {@link LanguageDescription} with typed {@link Processor}, {@link Endian},
     * and size. Falls back to parsing the {@code "processor:endian:size:variant"}
     * string directly if the service is unavailable or the language is unknown.
     */
    private static ClangArch resolveArch(String languageId)
    {
        if (languageId == null || languageId.isEmpty())
            return null;

        // Primary path: use Ghidra's language service for proper resolution
        try
        {
            LanguageService svc = DefaultLanguageService.getLanguageService();
            LanguageDescription desc = svc.getLanguageDescription(new LanguageID(languageId));
            return ClangArch.find(desc);
        }
        catch (Exception ignored)
        {
            // Service not initialized or language not found -- fall through
        }

        // Fallback: parse "processor:endian:size[:variant]" manually
        String[] parts = languageId.split(":");
        if (parts.length < 3)
            return null;

        try
        {
            Endian endian = Endian.toEndian(parts[1]);
            int size = Integer.parseInt(parts[2]);
            return ClangArch.find(parts[0], endian, size);
        }
        catch (IllegalArgumentException ignored)
        {
            return null;
        }
    }
}
