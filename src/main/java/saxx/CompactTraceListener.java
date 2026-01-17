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

import java.io.PrintStream;
import java.util.*;

/**
 * Compact YAML-like trace output with all instruction info.
 */
public class CompactTraceListener implements TraceListener {
    private final PrintStream out;
    private int depth = 0;
    private String currentNode = null;
    private final Map<String, String> fileAliases = new LinkedHashMap<>();
    private int nextAlias = 0;

    public CompactTraceListener(PrintStream out) {
        this.out = out;
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
            int totalWidth = 72;
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
        if (t instanceof ValueOf) return "value-of";
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
            if (t instanceof ValueOf) {
                Expression select = ((ValueOf) t).getSelect();
                if (select != null) {
                    String s = select.toShortString();
                    if (s.length() > 40) s = s.substring(0, 37) + "...";
                    return "select=\"" + s + "\"";
                }
            }
            if (t instanceof FixedElement) {
                return null;  // name shown in instruction type
            }
            if (t instanceof FixedAttribute) {
                NodeName name = ((FixedAttribute) t).getAttributeName();
                if (name != null) {
                    return "@" + name.getDisplayName();
                }
            }
            if (t instanceof Block) {
                int size = ((Block) t).size();
                return "[" + size + " items]";
            }
            if (t instanceof CallTemplate) {
                // Template name access varies by Saxon version
                return null;
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
