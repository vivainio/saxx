---
name: saxx
description: Validate XSLT stylesheets and run transformations using Saxon
---

# saxx - XSLT Validation Tool

Use this skill to validate XSLT stylesheets against user test directories.

## Important: Output Files

**Do not store output files (error reports, mock JSON files, transformation results) inside this git repository.** Use `/tmp/` or directories outside the repo to avoid accidental commits.

## Building

Before using saxx, ensure it's built:

```bash
python tasks.py build  # Downloads deps and builds the JAR
```

The JAR is at `target/saxx.jar`.

## Running Validation

```bash
# Single file
./saxx check template.xsl

# Directory (non-recursive)
./saxx check /path/to/xslt/

# Directory (recursive)
./saxx check -r /path/to/xslt/

# Skip fragment files (files imported by others)
./saxx check -r --skip-fragments /path/to/xslt/

# Deep check (compile + attempt transform to catch runtime errors)
./saxx check -r --skip-fragments --deep /path/to/xslt/

# Deep check with execution trace
./saxx check --deep --trace template.xsl

# Trace to file instead of stderr
./saxx check --deep --trace /tmp/trace.txt template.xsl

# With mock extension functions
./saxx check --deep --mocks /tmp/mocks.json /path/to/xslt/
```

## Running Transformations

```bash
./saxx transform -s stylesheet.xsl input.xml
./saxx transform -s stylesheet.xsl input.xml -o output.xml

# With execution trace (to stderr)
./saxx transform -s stylesheet.xsl input.xml --trace

# Trace to file
./saxx transform -s stylesheet.xsl input.xml --trace /tmp/trace.txt
```

## Execution Tracing

The `--trace` flag shows XSLT execution flow with actual values:

```
/:
  template match="/"                                                    A:3
    <result>                                                            A:4
      call-template ($id='ABC-123', $name='Test User')                  A:5
        template name="format"                                         A:11
          param $id                                                    A:12
          param $name                                                  A:13
          <item>                                                       A:14
            @id = "ABC-123"                                            A:14
            @name = "Test User"                                        A:14

Files:
  A = stylesheet.xsl
```

## Mock Extension Functions

Global mocks are loaded automatically from:
- **Linux/macOS**: `~/.config/saxx/mocks.json`
- **Windows**: `%APPDATA%\saxx\mocks.json`

For additional mocks or overrides, use `--mocks`:

```json
{
  "xalan://com.example.Extensions": {
    "_elements": ["init", "setup"],
    "functionName": "return value",
    "boolFunc": true,
    "numFunc": 42,
    "voidFunc": null
  }
}
```

- `_elements`: Extension element names to treat as no-ops (e.g., `<ns:init/>`)
- Other entries: Function name → return value (string, boolean, number, or null)

## Extracting XPath Mapping

```bash
./saxx map input.xml                # Extract all XPath paths and values
./saxx map input.xml --include-text # Include text nodes
```

## Understanding Output

Successful:
```
OK: /path/to/valid.xsl

Checked 1 file(s), 0 error(s)
```

Failed:
```
FAIL: /path/to/broken.xsl
  Error at xsl:template on line 15: XTSE0500 Required attribute 'match' or 'name' is missing

Checked 1 file(s), 1 error(s)
```

With fragments skipped:
```
Checked 50 file(s), 3 error(s), 12 skipped (fragments)
```

Exit code is 0 if all pass, 1 if any errors.

## When to Use

- User asks to validate XSLT files
- User asks to check if stylesheets compile
- User asks to run transformations
- User provides a directory of XSLT templates to test
