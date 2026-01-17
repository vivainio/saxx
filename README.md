# saxx

XSLT validation and transformation CLI tool powered by Saxon.

## Installation

Requires Java 11+ and Maven.

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

## License

MIT
