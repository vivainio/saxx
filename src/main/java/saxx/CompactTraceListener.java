package saxx;

import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.expr.instruct.*;
import net.sf.saxon.lib.TraceListener;
import net.sf.saxon.om.*;
import net.sf.saxon.s9api.Location;
import net.sf.saxon.trace.Traceable;
import net.sf.saxon.tree.AttributeLocation;
import net.sf.saxon.trans.Mode;
import net.sf.saxon.tree.util.Navigator;
import net.sf.saxon.expr.Expression;
import net.sf.saxon.expr.instruct.Block;
import net.sf.saxon.expr.instruct.Choose;
import net.sf.saxon.expr.instruct.NamedTemplate;
import net.sf.saxon.expr.instruct.LocalParam;
import net.sf.saxon.expr.StringLiteral;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compact YAML-like trace output with all instruction info.
 */
public class CompactTraceListener implements TraceListener {
    private final PrintStream out;
    private int depth = 0;
    private String currentNode = null;
    private final Map<String, String> fileAliases = new LinkedHashMap<>();
    private final Map<String, List<String>> fileCache = new HashMap<>();
    private int nextAlias = 0;
    private boolean showNextWhen = false;
    // Pattern for extracting test attribute (used for choose/when detection)
    private static final Pattern TEST_PATTERN = Pattern.compile("test\\s*=\\s*\"([^\"]*)\"|test\\s*=\\s*'([^']*)'");
    // Common XPath wrapper functions to unwrap for cleaner display
    private static final Pattern NORMALIZE_SPACE_PATTERN = Pattern.compile("^(?:fn:)?normalize-space\\((.+)\\)$");

    private static final String XSLT_NS = "http://www.w3.org/1999/XSL/Transform";

    public CompactTraceListener(PrintStream out) {
        this.out = out;
    }

    private List<String> getFileLines(String systemId) {
        return fileCache.computeIfAbsent(systemId, k -> {
            try {
                URI uri = new URI(systemId);
                return Files.readAllLines(Paths.get(uri));
            } catch (Exception e) {
                return Collections.emptyList();
            }
        });
    }

    /**
     * Unwrap NestedLocation to find the containing AttributeLocation.
     */
    private AttributeLocation unwrapToAttributeLocation(Location loc) {
        while (loc != null && !(loc instanceof AttributeLocation)) {
            if (loc instanceof net.sf.saxon.expr.parser.Loc) {
                break; // Simple location, no parent
            }
            try {
                java.lang.reflect.Method m = loc.getClass().getMethod("getContainingLocation");
                loc = (Location) m.invoke(loc);
            } catch (Exception e) {
                break;
            }
        }
        return (loc instanceof AttributeLocation) ? (AttributeLocation) loc : null;
    }

    /**
     * Find the xsl:when or xsl:otherwise condition by scanning backwards in the source file.
     */
    private String findWhenCondition(String systemId, int actionLine) {
        List<String> lines = getFileLines(systemId);
        // Scan backwards from action line to find xsl:when or xsl:otherwise
        for (int i = actionLine - 2; i >= 0 && i >= actionLine - 10; i--) {
            String line = lines.get(i);
            if (line.contains("<xsl:otherwise") || line.contains("xsl:otherwise>")) {
                return "otherwise";
            }
            Matcher m = TEST_PATTERN.matcher(line);
            if (m.find()) {
                String result = m.group(1);
                return result != null ? result : m.group(2);
            }
            // Stop if we hit the choose itself
            if (line.contains("<xsl:choose")) {
                break;
            }
        }
        return null;
    }

    /**
     * Unwrap normalize-space() wrapper for cleaner display.
     * e.g., "normalize-space(cbc:ID)" -> "cbc:ID"
     */
    private String simplifyXPath(String xpath) {
        if (xpath == null) return null;
        Matcher m = NORMALIZE_SPACE_PATTERN.matcher(xpath);
        if (m.matches()) {
            return m.group(1);
        }
        return xpath;
    }

    /**
     * Simplify XPath within a larger expression (e.g., conditions, parameters).
     * Replaces all occurrences of normalize-space(X) with X.
     */
    private String simplifyXPathInExpr(String expr) {
        if (expr == null) return null;
        String result = expr;
        // Iteratively remove normalize-space wrappers (handles nesting)
        String prev;
        do {
            prev = result;
            // Handle simple case: normalize-space(path) where path has no parens
            result = result.replaceAll("(?:fn:)?normalize-space\\(([^()]+)\\)", "$1");
            // Handle case with one level of nested parens: normalize-space(func(x))
            result = result.replaceAll("(?:fn:)?normalize-space\\(([^()]*\\([^()]*\\)[^()]*)\\)", "$1");
        } while (!result.equals(prev));
        return result;
    }

    /**
     * Get original XPath from an Expression's location.
     */
    private String getOriginalXPathFromExpr(Expression expr, String attrName) {
        if (expr == null) return null;
        AttributeLocation attrLoc = unwrapToAttributeLocation(expr.getLocation());
        if (attrLoc == null) return null;
        NodeInfo elem = attrLoc.getElementNode();
        if (elem == null) return null;
        return elem.getAttributeValue("", attrName);
    }

    private String getFileAlias(String systemId) {
        if (systemId == null) return "?";
        return fileAliases.computeIfAbsent(systemId, k -> {
            // Generate alias: A, B, C, ... Z, AA, AB, ...
            int n = nextAlias++;
            StringBuilder sb = new StringBuilder();
            do {
                sb.insert(0, (char) ('A' + (n % 26)));
                n = n / 26 - 1;
            } while (n >= 0);
            return sb.toString();
        });
    }

    @Override
    public void setOutputDestination(net.sf.saxon.lib.Logger stream) {}

    @Override
    public void open(net.sf.saxon.Controller controller) {}

    @Override
    public void close() {
        if (!fileAliases.isEmpty()) {
            out.println();
            out.println("Files:");
            for (Map.Entry<String, String> e : fileAliases.entrySet()) {
                out.printf("  %s = %s%n", e.getValue(), getShortModule(e.getKey()));
            }
        }
    }

    @Override
    public void enter(Traceable traceable, Map<String, Object> properties, XPathContext context) {
        try {
            String node = null;
            Item contextItem = context.getContextItem();
            if (contextItem instanceof NodeInfo) {
                node = Navigator.getPath((NodeInfo) contextItem);
            }

            // Get location info
            Location loc = traceable.getLocation();
            String alias = getFileAlias(loc.getSystemId());
            int line = loc.getLineNumber();
            String locRef = alias + ":" + line;

            // Get instruction type and details
            String type = getInstructionType(traceable);
            if (type == null) {
                depth++;
                return;  // Skip internal/noise instructions
            }
            String detail = getInstructionDetail(traceable, context);

            // Special handling for LocalParam to show value
            if (traceable instanceof LocalParam) {
                String paramValue = getParamValue((LocalParam) traceable, context, properties);
                if (paramValue != null) {
                    detail = paramValue;
                }
            }

            // Track Choose to show which branch was taken
            if (traceable instanceof Choose) {
                showNextWhen = true;
                depth++;
                return;  // Don't print choose line yet - we'll show it with the branch
            } else if (showNextWhen) {
                showNextWhen = false;
                String whenCond = findWhenCondition(loc.getSystemId(), line);
                if (whenCond != null) {
                    indent();
                    out.printf("  choose -> %s%n", simplifyXPathInExpr(whenCond));
                }
            }

            // Print node change
            if (node != null && !node.equals(currentNode)) {
                indent();
                out.printf("%s:%n", node);
                currentNode = node;
            }

            // Build instruction text
            String instr;
            if (detail != null && !detail.isEmpty()) {
                instr = type.isEmpty() ? detail : type + " " + detail;
            } else {
                instr = type;
            }

            // Print with right-aligned location
            int indentSize = Math.min(depth, 20) * 2 + 2;  // current indent + leading spaces
            int totalWidth = 100;

            // Handle multi-line detail (e.g., call-template with many params)
            if (instr.contains("\n")) {
                String[] lines = instr.split("\n");
                int padWidth = totalWidth - indentSize - locRef.length();
                indent();
                out.printf("  %-" + Math.max(padWidth, lines[0].length() + 2) + "s%s%n", lines[0], locRef);
                for (int i = 1; i < lines.length; i++) {
                    indent();
                    out.printf("    %s%n", lines[i]);
                }
            } else {
                int padWidth = totalWidth - indentSize - locRef.length();
                indent();
                out.printf("  %-" + Math.max(padWidth, instr.length() + 2) + "s%s%n", instr, locRef);
            }

            depth++;
        } catch (Exception e) {
            // Ignore
        }
    }

    @Override
    public void leave(Traceable traceable) {
        if (depth > 0) depth--;
        if (traceable instanceof Block) {
            indent();
            out.println("  }");
        }
    }

    @Override
    public void startCurrentItem(Item item) {
        if (item instanceof NodeInfo) {
            String node = Navigator.getPath((NodeInfo) item);
            if (!node.equals(currentNode)) {
                indent();
                out.printf("%s:%n", node);
                currentNode = node;
            }
        }
    }

    @Override
    public void endCurrentItem(Item item) {}

    @Override
    public void startRuleSearch() {}

    @Override
    public void endRuleSearch(Object rule, Mode mode, Item item) {}

    private void indent() {
        int spaces = Math.min(depth, 20);  // cap indentation
        for (int i = 0; i < spaces; i++) {
            out.print("  ");
        }
    }

    private String getInstructionType(Traceable t) {
        if (t instanceof TemplateRule) return "template";
        if (t instanceof ApplyTemplates) return "apply-templates";
        if (t instanceof CallTemplate) return "call-template";
        if (t instanceof ForEach) return "for-each";
        if (t instanceof Choose) return "choose";
        if (t instanceof ValueOf) return "!";
        if (t instanceof Copy) return "copy";
        if (t instanceof CopyOf) return "copy-of";
        if (t instanceof FixedElement) {
            NodeName name = ((FixedElement) t).getFixedElementName();
            return name != null ? "<" + name.getDisplayName() + ">" : "element";
        }
        if (t instanceof FixedAttribute) return "";
        if (t instanceof Comment) return "comment";
        if (t instanceof ProcessingInstruction) return "pi";
        if (t instanceof Block) return "{";
        if (t instanceof NamedTemplate) {
            NamedTemplate nt = (NamedTemplate) t;
            return nt.getTemplateName() != null
                ? "template name=\"" + nt.getTemplateName().getDisplayName() + "\""
                : "template";
        }
        if (t instanceof LocalParam) {
            LocalParam lp = (LocalParam) t;
            return "param $" + lp.getVariableQName().getDisplayName();
        }
        // Message class may not exist in all Saxon versions
        if (t instanceof ResultDocument) return "result-document";
        if (t instanceof IterateInstr) return "iterate";
        // Filter out internal expression types that are noise in trace
        String className = t.getClass().getSimpleName();
        if (className.contains("Converter") || className.contains("Trace") ||
            className.equals("DocumentInstr") || className.equals("Atomizer")) {
            return null;  // Skip these in trace output
        }
        if (t instanceof Instruction) return className.toLowerCase();
        return className;
    }

    private String getInstructionDetail(Traceable t, XPathContext context) {
        try {
            if (t instanceof TemplateRule) {
                // Try to extract from pattern's location (AttributeLocation)
                net.sf.saxon.pattern.Pattern pattern = ((TemplateRule) t).getMatchPattern();
                AttributeLocation attrLoc = unwrapToAttributeLocation(pattern.getLocation());
                if (attrLoc != null) {
                    NodeInfo elem = attrLoc.getElementNode();
                    if (elem != null) {
                        String fromSource = elem.getAttributeValue("", "match");
                        if (fromSource != null) {
                            return "match=\"" + simplifyXPathInExpr(fromSource) + "\"";
                        }
                    }
                }
                return "match=\"" + simplifyXPathInExpr(pattern.toString()) + "\"";
            }
            if (t instanceof ApplyTemplates) {
                Expression select = ((ApplyTemplates) t).getSelectExpression();
                if (select != null) {
                    // Try to extract from expression's location (AttributeLocation)
                    String fromSource = getOriginalXPathFromExpr(select, "select");
                    if (fromSource != null) {
                        return "select=\"" + simplifyXPathInExpr(fromSource) + "\"";
                    }
                    return "select=\"" + simplifyXPathInExpr(select.toString()) + "\"";
                }
            }
            if (t instanceof ForEach) {
                Expression select = ((ForEach) t).getSelectExpression();
                if (select != null) {
                    // Try to extract from expression's location (AttributeLocation)
                    String fromSource = getOriginalXPathFromExpr(select, "select");
                    if (fromSource != null) {
                        return "select=\"" + simplifyXPathInExpr(fromSource) + "\"";
                    }
                    return "select=\"" + simplifyXPathInExpr(select.toString()) + "\"";
                }
            }
            if (t instanceof Choose) {
                Choose ch = (Choose) t;
                int n = ch.size();
                // Use Saxon's representation for first condition
                Expression cond = ch.getCondition(0);
                return "[" + n + "] " + cond.toString();
            }
            if (t instanceof ValueOf) {
                Expression select = ((ValueOf) t).getSelect();
                if (select instanceof StringLiteral) {
                    String s = ((StringLiteral) select).getString().toString();
                    return "\"" + s + "\"";
                }
                // Try to extract from expression's location (AttributeLocation)
                String fromSource = getOriginalXPathFromExpr(select, "select");
                if (fromSource != null) {
                    return simplifyXPathInExpr(fromSource);
                }
                // Fallback to Saxon's representation
                if (select != null) {
                    return simplifyXPathInExpr(select.toString());
                }
            }
            if (t instanceof FixedElement) {
                return null;  // name shown in instruction type
            }
            if (t instanceof FixedAttribute) {
                FixedAttribute fa = (FixedAttribute) t;
                NodeName name = fa.getAttributeName();
                String attrName = name != null ? name.getDisplayName() : "?";
                Expression select = fa.getSelect();
                if (select != null) {
                    // Try to evaluate the expression to get the actual value
                    try {
                        String value = select.evaluateAsString(context).toString();
                        return "@" + attrName + " = \"" + value + "\"";
                    } catch (Exception e) {
                        if (select instanceof StringLiteral) {
                            return "@" + attrName + " = \"" + ((StringLiteral) select).getString() + "\"";
                        }
                        return "@" + attrName + " = " + select.toString();
                    }
                }
                return "@" + attrName;
            }
            if (t instanceof CallTemplate) {
                CallTemplate ct = (CallTemplate) t;
                WithParam[] params = ct.getActualParams();
                if (params != null && params.length > 0) {
                    List<String> paramStrs = new ArrayList<>();
                    for (WithParam wp : params) {
                        StringBuilder ps = new StringBuilder();
                        ps.append("$").append(wp.getVariableQName().getLocalPart());
                        Expression select = wp.getSelectExpression();
                        if (select != null) {
                            // Try to get from expression's location (AttributeLocation)
                            String fromSource = getOriginalXPathFromExpr(select, "select");
                            if (fromSource != null) {
                                ps.append("=").append(simplifyXPathInExpr(fromSource));
                            } else if (select instanceof StringLiteral) {
                                ps.append("=\"").append(((StringLiteral) select).getString()).append("\"");
                            }
                            // Skip verbose toString() for complex expressions - just show param name
                        }
                        paramStrs.add(ps.toString());
                    }
                    // If more than 3 params, show one per line
                    if (paramStrs.size() > 3) {
                        return "\n    " + String.join("\n    ", paramStrs);
                    }
                    return "(" + String.join(", ", paramStrs) + ")";
                }
                return null;
            }
            if (t instanceof LocalParam) {
                // Value will be added via context in enter()
                return null;
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String getParamValue(LocalParam lp, XPathContext context, Map<String, Object> properties) {
        // Try to extract select attribute from expression's location (AttributeLocation)
        try {
            Expression select = lp.getSelectExpression();
            String fromSource = getOriginalXPathFromExpr(select, "select");
            if (fromSource != null) {
                return ": " + simplifyXPathInExpr(fromSource);
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }

    private String getShortModule(String systemId) {
        if (systemId == null) return "?";
        int lastSlash = systemId.lastIndexOf('/');
        return lastSlash >= 0 ? systemId.substring(lastSlash + 1) : systemId;
    }
}
