# saxx

XSLT validation and transformation CLI tool powered by Saxon.

## Installation

Requires Java 11+.

Using [zipget](https://github.com/vivainio/zipget-rs):

```bash
zipget install vivainio/saxx
```

### Build from source

Requires Maven.

```bash
python tasks.py deps   # Download Saxon-HE fork (one-time)
python tasks.py build  # Build with Maven
```

## Usage

```bash
./saxx <command> [options]
```

## Commands

### check - Validate XSLT stylesheets

```bash
# Check a single file
./saxx check stylesheet.xsl

# Check all XSLT files in a directory
./saxx check ./xslt/

# Recursive check
./saxx check -r ./xslt/

# Deep check (runs transform to catch runtime errors)
./saxx check --deep stylesheet.xsl

# Skip fragment files (imported/included by other stylesheets)
./saxx check -r --skip-fragments ./xslt/

# With mock extension functions
./saxx check --deep --mocks mocks.json stylesheet.xsl
```

### transform - Transform XML using XSLT

```bash
# Transform to stdout
./saxx transform -s stylesheet.xsl input.xml

# Transform to file
./saxx transform -s stylesheet.xsl input.xml -o output.xml

# With execution trace
./saxx transform -s stylesheet.xsl input.xml --trace
```

#### Execution Tracing

The `--trace` flag shows XSLT execution flow:

```
/:
  template match="/"                                                                             A:3
    <output>                                                                                     A:4
      <header>                                                                                   A:5
        <title>                                                                                  A:6
          ! "Test"                                                                               A:6
      <body>                                                                                     A:8
        for-each select="//item"                                                                 A:9
        /root/item[1]:
          <row>                                                                                 A:10
            ! .                                                                                 A:10

Files:
  A = stylesheet.xsl
```

### map - Extract XPath paths from XML

```bash
# Show all elements and attributes
./saxx map input.xml

# Include text nodes
./saxx map --include-text input.xml
```

## Mock Extension Functions

For `--deep` checks, you can mock external extension functions with a JSON file:

```json
{
  "http://example.com/ns": {
    "_elements": ["init", "setup"],
    "getValue": "mock-value",
    "isEnabled": true,
    "getCount": 42
  }
}
```

- `_elements`: Extension element names to ignore (treated as no-ops)
- Other entries: Function name → return value (string, boolean, number, or null)

### Global Mocks

Global mocks are loaded automatically from:
- **Linux/macOS**: `~/.config/saxx/mocks.json`
- **Windows**: `%APPDATA%\saxx\mocks.json`

On first run, an empty `{}` file is created if it doesn't exist.

Example:
```json
{
  "xalan://com.example.Extensions": {
    "_elements": ["init", "setup"],
    "getValue": "default"
  }
}
```

The `--mocks` option adds to (or overrides) the global mocks.

## License

MIT
