package saxx;

import net.sf.saxon.expr.XPathContext;
import net.sf.saxon.expr.instruct.*;
import net.sf.saxon.lib.TraceListener;
import net.sf.saxon.om.*;
import net.sf.saxon.s9api.Location;
import net.sf.saxon.trace.Traceable;
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
    private boolean insideChoose = false;
    private boolean showNextWhen = false;
    private static final Pattern SELECT_PATTERN = Pattern.compile("select\\s*=\\s*\"([^\"]*)\"|select\\s*=\\s*'([^']*)'");
    private static final Pattern TEST_PATTERN = Pattern.compile("test\\s*=\\s*\"([^\"]*)\"|test\\s*=\\s*'([^']*)'");

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

    private String extractSelect(String systemId, int line) {
        return extractAttribute(systemId, line, SELECT_PATTERN);
    }

    private String extractTest(String systemId, int line) {
        return extractAttribute(systemId, line, TEST_PATTERN);
    }

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

    private String extractAttribute(String systemId, int line, Pattern pattern) {
        List<String> lines = getFileLines(systemId);
        if (line > 0 && line <= lines.size()) {
            String content = lines.get(line - 1);
            Matcher m = pattern.matcher(content);
            if (m.find()) {
                // Pattern has two groups: one for double-quoted, one for single-quoted
                String result = m.group(1);
                return result != null ? result : m.group(2);
            }
        }
        return null;
    }

    private String extractAttributeValue(String systemId, int line, String attrName) {
        List<String> lines = getFileLines(systemId);
        if (line > 0 && line <= lines.size()) {
            String content = lines.get(line - 1);
            // Match attrName="value" or attrName='value'
            Pattern p = Pattern.compile(attrName + "\\s*=\\s*\"([^\"]*)\"|" + attrName + "\\s*=\\s*'([^']*)'");
            Matcher m = p.matcher(content);
            if (m.find()) {
                String result = m.group(1);
                return result != null ? result : m.group(2);
            }
        }
        return null;
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
            String detail = getInstructionDetail(traceable);

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
                    out.printf("  choose -> %s%n", whenCond);
                }
            }

            // Print node change
            if (node != null && !node.equals(currentNode)) {
                indent();
                out.printf("%s:%n", node);
                currentNode = node;
            }

            // Build instruction text
            String instr = detail != null && !detail.isEmpty()
                ? type + " " + detail
                : type;

            // Print with right-aligned location
            int indent = Math.min(depth, 20) * 2 + 2;  // current indent + leading spaces
            int totalWidth = 100;
            int padWidth = totalWidth - indent - locRef.length();
            indent();
            out.printf("  %-" + Math.max(padWidth, instr.length() + 2) + "s%s%n", instr, locRef);

            depth++;
        } catch (Exception e) {
            // Ignore
        }
    }

    @Override
    public void leave(Traceable traceable) {
        if (depth > 0) depth--;
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
        if (t instanceof FixedAttribute) return "attribute";
        if (t instanceof Comment) return "comment";
        if (t instanceof ProcessingInstruction) return "pi";
        if (t instanceof Block) return "block";
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
        if (t instanceof Instruction) return t.getClass().getSimpleName().toLowerCase();
        return t.getClass().getSimpleName();
    }

    private String getInstructionDetail(Traceable t) {
        try {
            if (t instanceof TemplateRule) {
                return "match=\"" + ((TemplateRule) t).getMatchPattern().toShortString() + "\"";
            }
            if (t instanceof ApplyTemplates) {
                Expression select = ((ApplyTemplates) t).getSelectExpression();
                if (select != null) {
                    return "select=\"" + select.toShortString() + "\"";
                }
            }
            if (t instanceof ForEach) {
                Expression select = ((ForEach) t).getSelectExpression();
                if (select != null) {
                    return "select=\"" + select.toShortString() + "\"";
                }
            }
            if (t instanceof Choose) {
                Choose ch = (Choose) t;
                int n = ch.size();
                // Try to extract test from source (look at next line for xsl:when)
                Location loc = t.getLocation();
                String test = extractTest(loc.getSystemId(), loc.getLineNumber() + 1);
                if (test != null) {
                    return "[" + n + "] " + test;
                }
                // Fallback to Saxon's representation
                Expression cond = ch.getCondition(0);
                return "[" + n + "] " + cond.toShortString();
            }
            if (t instanceof ValueOf) {
                Expression select = ((ValueOf) t).getSelect();
                if (select instanceof StringLiteral) {
                    String s = ((StringLiteral) select).getString().toString();
                    return "\"" + s + "\"";
                }
                // Try to extract from source
                Location loc = t.getLocation();
                String fromSource = extractSelect(loc.getSystemId(), loc.getLineNumber());
                if (fromSource != null) {
                    return fromSource;
                }
                // Fallback to Saxon's representation
                if (select != null) {
                    return select.toShortString();
                }
            }
            if (t instanceof FixedElement) {
                return null;  // name shown in instruction type
            }
            if (t instanceof FixedAttribute) {
                FixedAttribute fa = (FixedAttribute) t;
                NodeName name = fa.getAttributeName();
                String attrName = name != null ? name.getDisplayName() : "?";
                // Try to extract value from source
                Location loc = t.getLocation();
                String fromSource = extractAttributeValue(loc.getSystemId(), loc.getLineNumber(), attrName);
                if (fromSource != null) {
                    return "@" + attrName + " = \"" + fromSource + "\"";
                }
                // Fallback to Saxon expression
                Expression select = fa.getSelect();
                if (select != null) {
                    if (select instanceof StringLiteral) {
                        return "@" + attrName + " = \"" + ((StringLiteral) select).getString() + "\"";
                    }
                    return "@" + attrName + " = " + select.toShortString();
                }
                return "@" + attrName;
            }
            if (t instanceof Block) {
                int size = ((Block) t).size();
                return "[" + size + " items]";
            }
            if (t instanceof CallTemplate) {
                CallTemplate ct = (CallTemplate) t;
                WithParam[] params = ct.getActualParams();
                if (params != null && params.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (WithParam wp : params) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append("$").append(wp.getVariableQName().getLocalPart());
                        Expression select = wp.getSelectExpression();
                        if (select != null) {
                            // Try to get from source first
                            Location loc = wp.getSelectOperand().getChildExpression().getLocation();
                            String fromSource = extractSelect(loc.getSystemId(), loc.getLineNumber());
                            if (fromSource != null) {
                                sb.append("=").append(fromSource);
                            } else if (select instanceof StringLiteral) {
                                sb.append("=\"").append(((StringLiteral) select).getString()).append("\"");
                            } else {
                                sb.append("=").append(select.toShortString());
                            }
                        }
                    }
                    return "(" + sb + ")";
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
        // Try to extract select attribute from source (shows actual default value expression)
        try {
            Location loc = lp.getLocation();
            String fromSource = extractSelect(loc.getSystemId(), loc.getLineNumber());
            if (fromSource != null) {
                return "select=\"" + fromSource + "\"";
            }
        } catch (Exception e) {
            // Ignore
        }
        // No select in source - param value comes from with-param (not available at trace time)
        return null;
    }

    private String getShortModule(String systemId) {
        if (systemId == null) return "?";
        int lastSlash = systemId.lastIndexOf('/');
        return lastSlash >= 0 ? systemId.substring(lastSlash + 1) : systemId;
    }
}
