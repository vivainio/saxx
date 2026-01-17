# Agent Instructions

## Project Overview

saxx is an XSLT validation and transformation CLI tool powered by Saxon.

## Build Instructions

```bash
python tasks.py deps   # Download Saxon-HE fork from GitHub releases (one-time)
python tasks.py build  # Build with Maven
```

## Run

```bash
./saxx <command> [options]
```

## Commands

- `saxx check <path>` - Validate/compile XSLT stylesheets
- `saxx transform -s <stylesheet> <input>` - Transform XML using XSLT
- `saxx map <input>` - Extract XPath paths and values from XML

## Key Options

- `saxx check --deep` - Also run transform to catch runtime errors
- `saxx check --mocks <file>` - JSON file with mock extension functions
- `saxx transform --trace` - Trace XSLT execution
- `saxx transform -o <file>` - Output to file instead of stdout

## Project Structure

```
src/main/java/saxx/
├── Main.java                  # CLI entry point, commands
├── CompactTraceListener.java  # --trace output formatting
└── MockExtensionFunction.java # Mock extensions for deep checks
```

## Dependencies

- Saxon-HE fork (downloaded via `tasks.py deps`)
- picocli (from Maven Central)

## Rules

- Never write test outputs or temporary files to this repository directory
