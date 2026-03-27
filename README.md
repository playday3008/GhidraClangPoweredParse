# GhidraClangPoweredParse

A [Ghidra](https://ghidra-sre.org/) extension that parses C/C++ headers using **libclang** and imports the extracted types into Ghidra's data type manager. Built on JDK 21+ Panama FFI — no JNA, no jextract, no third-party native dependencies.

## Features

- Parse C/C++ source and header files with a real compiler frontend (libclang)
- Import structs, unions, enums, typedefs, and function signatures
- Bit-field and `__attribute__((packed))` support
- 24 target architectures (x86, ARM, MIPS, PowerPC, SPARC, RISC-V, M68K, AVR, LoongArch, S390, and more)
- Bundled statically-linked libclang for Linux, macOS, and Windows (x86_64 + ARM64) — no system LLVM required
- Save/load parse profiles (`.prf`), compatible with Ghidra's built-in C parser
- Commit types to the current program or export to a standalone `.gdt` archive

## Requirements

- **Ghidra** 12+
- **JDK 21 or 22** (Panama FFI is preview in 21, stable in 22+)

## Installation

1. Download a release ZIP (or [build from source](#building-from-source))
2. In Ghidra: **File > Install Extensions...** > click the **+** icon > select the ZIP
3. Restart Ghidra
4. If using JDK 21, enable preview features: edit `<ghidra_install>/support/launch.properties` and add `--enable-preview` to the `VMARGS` line
5. Open a tool (e.g. CodeBrowser), go to **File > Configure** > click **Configure** under **Ghidra Code** > enable the **GCPPPlugin (Clang C/C++ Parser)** plugin

## Usage

1. Open a program in the CodeBrowser
2. Go to **File > Parse C/C++ Source (Clang Powered)...**
3. Add source/header files and include paths
4. Configure parse options and select the target architecture
5. Click **Parse to Program** to import types directly, or **Parse to File...** to export to a `.gdt` archive

## Building from Source

### Prerequisites

| Variable | Required | Description |
| --- | --- | --- |
| `GHIDRA_INSTALL_DIR` | Yes | Path to a Ghidra installation |
| `JAVA_HOME` | Maybe | Path to JDK 21 or 22 (if system default differs) |

### Build

```bash
./gradlew                     # build universal ZIP (all platforms)
./gradlew createDistribution_linux_x86_64   # platform-specific (smaller)
```

Output goes to `dist/`.

Platform-specific tasks: `linux_x86_64`, `linux_arm_64`, `mac_x86_64`, `mac_arm_64`, `win_x86_64`, `win_arm_64`.

## Acknowledgements

Heavily inspired by [GhidraClangTypes](https://github.com/Adubbz/GhidraClangTypes) by [@Adubbz](https://github.com/Adubbz) — the original libclang-based type parser for Ghidra that got this idea rolling.

## License

[Apache License 2.0](LICENSE)
