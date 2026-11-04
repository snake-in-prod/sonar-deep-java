package io.github.snakeinprod.deepjava.prepass;

import io.github.snakeinprod.deepjava.prepass.ClassFacts.Edge;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.MethodFacts;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.Sink;
import io.github.snakeinprod.deepjava.prepass.Summaries.Finding;
import io.github.snakeinprod.deepjava.prepass.Summaries.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the symbolic labels of {@link ClassFacts.TaintFacts} project-wide (context-insensitive summaries): which
 * parameters and sources each method returns, which parameters reach which sinks, and finally which sources reach
 * which sinks.
 */
final class TaintSolver {

  private static final Set<String> UNTRUSTED = Set.of("USER", "COOKIE", "JWT", "NET", "MSG", "CMDLINE", "ENV", "FILE", "DESER", "DATA", "CONFIG");
  private static final Set<String> REMOTE = Set.of("USER", "COOKIE", "JWT", "NET", "MSG");

  /** A parameter of a method reaches a sink (possibly in a callee). */
  private record SinkFlow(int param, Sink sink, ClassFacts where, String via) {
  }

  private final Summaries s;
  private final Map<Method, Set<String>> returns = new HashMap<>();
  private final Map<Method, Set<SinkFlow>> sinkParams = new HashMap<>();
  private final Map<Method, Map<Integer, Edge>> edges = new HashMap<>();
  private final Map<String, Finding> findings = new LinkedHashMap<>();
  private final Map<Method, Map<String, Set<String>>> resolved = new HashMap<>();

  TaintSolver(Summaries s) {
    this.s = s;
  }

  @SuppressWarnings("unchecked")
  List<Finding> run() {
    for (ClassFacts c : s.classes().values()) {
      for (Map.Entry<String, MethodFacts> e : c.methods().entrySet()) {
        Method m = new Method(c, e.getKey(), e.getValue());
        for (Sink sink : e.getValue().taint().sinks()) {
          report(sink, c, resolveAll(m, sink.labels(), 0), null);
        }
        for (Edge edge : e.getValue().taint().edges()) {
          for (Method t : targets(edge)) {
            for (SinkFlow f : sinkParams(t, 0)) {
              if (f.param() < edge.args().size()) {
                Set<String> labels = resolveAll(m, accept(f.sink().kind(), edge.args().get(f.param())), 0);
                if ("SIZE".equals(f.sink().kind())) {
                  // a size is checked (or not) by the caller that passes it: report at the call, unless many callers
                  // pass sizes to the same helper (then the helper should check, see below)
                  Sink atCall = new Sink("SIZE", edge.line(), f.sink().labels(), f.sink().api() + " in " + t.display() + " at "
                    + Summaries.location(f.where(), f.sink().line()));
                  if (!labels.isEmpty()) {
                    String helper = f.where().name() + "|" + f.sink().line() + "|" + f.sink().api();
                    sizeCalls.computeIfAbsent(helper, k -> new ArrayList<>())
                      .add(new Object[] {atCall, c, labels, Summaries.location(c, edge.line()), f.sink(), f.where()});
                  }
                } else {
                  report(f.sink(), f.where(), labels, Summaries.location(c, edge.line()));
                }
              }
            }
          }
        }
      }
    }
    sizeCalls.forEach((helper, calls) -> {
      if (calls.size() <= MAX_SIZE_CALLERS) {
        calls.forEach(o -> report((Sink) o[0], (ClassFacts) o[1], (Set<String>) o[2], (String) o[3]));
      } else {
        Set<String> all = new HashSet<>();
        calls.forEach(o -> all.addAll((Set<String>) o[2]));
        report((Sink) calls.get(0)[4], (ClassFacts) calls.get(0)[5], all, calls.size() + " call sites, e.g. " + calls.get(0)[3]);
      }
    });
    return new ArrayList<>(findings.values());
  }

  /** A helper that many callers pass untrusted sizes to is reported once, where it uses the size. */
  private static final int MAX_SIZE_CALLERS = 3;

  private final Map<String, List<Object[]>> sizeCalls = new LinkedHashMap<>();

  private List<Method> targets(Edge edge) {
    List<Method> t = s.targets(edge.callKey());
    return t.size() > s.thresholds().maxTargets() ? List.of() : t;
  }

  private Edge edge(Method m, int site) {
    return edges.computeIfAbsent(m, k -> {
      Map<Integer, Edge> map = new HashMap<>();
      k.facts().taint().edges().forEach(e -> map.put(e.site(), e));
      return map;
    }).get(site);
  }

  private static boolean numeric(String label) {
    return label.endsWith("#");
  }

  private static String base(String label) {
    return numeric(label) ? label.substring(0, label.length() - 1) : label;
  }

  /** Labels that a sink of {@code kind} accepts: numbers only for size sinks, never for injection sinks. */
  private static Set<String> accept(String kind, Set<String> labels) {
    if ("SIZE".equals(kind)) {
      return labels;
    }
    Set<String> out = new HashSet<>();
    labels.forEach(l -> {
      if (!numeric(l)) {
        out.add(l);
      }
    });
    return out;
  }

  private Set<String> resolveAll(Method m, Set<String> labels, int depth) {
    Set<String> out = new HashSet<>();
    for (String l : labels) {
      out.addAll(resolve(m, l, depth));
    }
    return out;
  }

  /** A label of {@code m} in terms of {@code m}'s parameters ({@code P<i>}) and sources ({@code S...}). */
  private Set<String> resolve(Method m, String label, int depth) {
    String b = base(label);
    if (!b.startsWith("C")) {
      return Set.of(label);
    }
    Map<String, Set<String>> memo = resolved.computeIfAbsent(m, k -> new HashMap<>());
    Set<String> known = memo.get(label);
    if (known != null) {
      return known;
    }
    memo.put(label, Set.of());
    Set<String> r = Set.copyOf(resolveCall(m, label, b, depth));
    memo.put(label, r);
    return r;
  }

  private Set<String> resolveCall(Method m, String label, String b, int depth) {
    Edge e = edge(m, Integer.parseInt(b.substring(1)));
    if (e == null || depth > 12) {
      return Set.of();
    }
    Set<String> out = new HashSet<>();
    for (Method t : targets(e)) {
      for (String r : returns(t, depth + 1)) {
        boolean num = numeric(label) || numeric(r);
        String rb = base(r);
        if (rb.startsWith("P")) {
          int j = Integer.parseInt(rb.substring(1));
          if (j < e.args().size()) {
            for (String a : resolveAll(m, e.args().get(j), depth + 1)) {
              out.add(num && !numeric(a) ? a + "#" : a);
            }
          }
        } else {
          out.add(num ? rb + "#" : rb);
        }
      }
    }
    return out;
  }

  private Set<String> returns(Method t, int depth) {
    Set<String> r = returns.get(t);
    if (r != null) {
      return r;
    }
    returns.put(t, Set.of());
    r = resolveAll(t, t.facts().taint().returns(), depth);
    returns.put(t, r);
    return r;
  }

  private Set<SinkFlow> sinkParams(Method m, int depth) {
    Set<SinkFlow> r = sinkParams.get(m);
    if (r != null) {
      return r;
    }
    sinkParams.put(m, Set.of());
    Set<SinkFlow> out = new HashSet<>();
    for (Sink sink : m.facts().taint().sinks()) {
      for (String l : resolveAll(m, sink.labels(), depth)) {
        if (base(l).startsWith("P") && accepted(sink.kind(), l)) {
          out.add(new SinkFlow(Integer.parseInt(base(l).substring(1)), sink, m.owner(), null));
        }
      }
    }
    if (depth < 8) {
      for (Edge e : m.facts().taint().edges()) {
        for (Method t : targets(e)) {
          for (SinkFlow f : sinkParams(t, depth + 1)) {
            if (f.param() < e.args().size()) {
              for (String l : resolveAll(m, accept(f.sink().kind(), e.args().get(f.param())), depth)) {
                if (base(l).startsWith("P")) {
                  out.add(new SinkFlow(Integer.parseInt(base(l).substring(1)), f.sink(), f.where(), f.via()));
                }
              }
            }
          }
        }
      }
    }
    // ponytail: a cycle resolves with what was known when it closed; iterate to a fixpoint if recall matters
    sinkParams.put(m, Set.copyOf(out));
    return sinkParams.get(m);
  }

  private static boolean accepted(String kind, String label) {
    return "SIZE".equals(kind) ? numeric(label) : !numeric(label);
  }

  private void report(Sink sink, ClassFacts where, Set<String> labels, String passedAt) {
    for (String l : labels) {
      String b = base(l);
      // through a callee, the sink side already checked the kind of value (accept() filtered the caller side)
      if (!b.startsWith("S") || passedAt == null && !accepted(sink.kind(), l)) {
        continue;
      }
      String[] parts = b.substring(1).split("\\|", 3);
      String kind = parts[0];
      String rule = rule(sink.kind(), kind);
      if (rule == null) {
        continue;
      }
      String key = rule + "|" + where.sourcePath() + "|" + sink.line();
      String message = message(sink, kind, parts.length > 1 ? parts[1] : "?", parts.length > 2 ? parts[2] : "?", passedAt);
      findings.putIfAbsent(key, new Finding(rule, where.sourcePath(), sink.line(), message));
    }
  }

  private String rule(String sinkKind, String sourceKind) {
    if ("SECRET".equals(sourceKind)) {
      return Set.of("LOG", "LOGARG", "STDOUT", "EXCEPTION").contains(sinkKind) ? "SecretLeak" : null;
    }
    if (!UNTRUSTED.contains(sourceKind)) {
      return null;
    }
    // binary/text input only says how big something is; configuration only matters for commands
    if ("DATA".equals(sourceKind)) {
      return "SIZE".equals(sinkKind) && s.options().dataSources() ? "TaintedSize" : null;
    }
    if ("CONFIG".equals(sourceKind)) {
      return switch (sinkKind) {
        case "COMMAND" -> "TaintedCommand";
        case "PATH" -> "TaintedPath";
        case "SIZE" -> s.options().dataSources() ? "TaintedSize" : null;
        default -> null;
      };
    }
    if ("LOGARG".equals(sinkKind)) {
      return !s.options().logParametersSafe() && REMOTE.contains(sourceKind) ? "TaintedLog" : null;
    }
    return switch (sinkKind) {
      case "SQL" -> "TaintedSql";
      case "COMMAND" -> "TaintedCommand";
      case "PATH" -> "TaintedPath";
      case "SSRF" -> "TaintedUrl";
      case "LDAP" -> "TaintedLdap";
      case "XPATH" -> "TaintedXPath";
      case "LOG" -> REMOTE.contains(sourceKind) ? "TaintedLog" : null;
      case "HEADER" -> "TaintedHeader";
      case "REDIRECT" -> "TaintedRedirect";
      case "EXPRESSION" -> "TaintedExpression";
      case "FORMAT" -> REMOTE.contains(sourceKind) ? "TaintedFormatString" : null;
      case "SIZE" -> REMOTE.contains(sourceKind) ? "TaintedSize" : null;
      case "DECISION" -> Set.of("COOKIE", "JWT").contains(sourceKind) ? "TaintedSecurityDecision" : null;
      default -> null;
    };
  }

  private static String message(Sink sink, String kind, String what, String at, String passedAt) {
    String source = switch (kind) {
      case "USER" -> "user input";
      case "COOKIE" -> "a cookie";
      case "JWT" -> "an unverified JWT";
      case "ENV" -> "an environment variable";
      case "NET" -> "network input";
      case "MSG" -> "a received message";
      case "CMDLINE" -> "the command line";
      case "FILE" -> "file content";
      case "DESER" -> "deserialized data";
      case "DATA" -> "binary or text input";
      case "CONFIG" -> "configuration";
      case "SECRET" -> "a secret";
      default -> kind;
    };
    String target = switch (sink.kind()) {
      case "SQL" -> "SQL query";
      case "COMMAND" -> "OS command";
      case "PATH" -> "File path";
      case "SSRF" -> "Target of an outgoing connection";
      case "LDAP" -> "LDAP filter";
      case "XPATH" -> "XPath expression";
      case "LOG", "LOGARG" -> "Log message";
      case "STDOUT" -> "Standard output";
      case "EXCEPTION" -> "Exception message";
      case "HEADER" -> "HTTP header";
      case "REDIRECT" -> "Redirect target";
      case "EXPRESSION" -> "Evaluated expression";
      case "FORMAT" -> "Format string";
      case "SIZE" -> capitalize(sink.api());
      case "DECISION" -> "Security decision";
      default -> sink.kind();
    };
    String verb = "SECRET".equals(kind) ? " contains " : "DECISION".equals(sink.kind()) ? " based on " : " built from ";
    return target + ("SIZE".equals(sink.kind()) ? "" : " (" + sink.api() + ")") + verb + source + ": " + what + " at " + at
      + (passedAt != null ? " (passed at " + passedAt + ")" : "") + ".";
  }

  private static String capitalize(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }
}
