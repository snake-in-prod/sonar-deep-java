package io.github.snakeinprod.deepjava.prepass;

import io.github.snakeinprod.deepjava.prepass.ClassFacts.MethodFacts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Whole-program answers built from per-class facts, a class hierarchy and library models. */
public final class Summaries {

  /**
   * Why a call can return null.
   *
   * @param method   display name, e.g. {@code Config.host()}
   * @param location {@code File.java:12}, or null for library and annotated methods
   * @param cause    the callee whose result is returned, or null when null is returned directly
   * @param kind     how null arises
   * @param guards   method names that, called on the same receiver, make the result safe (library models)
   */
  public record NullReason(String method, String location, NullReason cause, Kind kind, Set<String> guards) {
    public enum Kind { RETURNS_NULL, RETURNS_CALL, ANNOTATED, LIBRARY }

    /** The null comes from a library model at the end of the chain (no call-site statistics needed). */
    public boolean fromLibrary() {
      NullReason r = this;
      while (r.cause() != null) {
        r = r.cause();
      }
      return r.kind() == Kind.LIBRARY;
    }

    /** e.g. {@code Config.java:4 via "Map.get()"}. */
    public String detail() {
      StringBuilder sb = new StringBuilder(switch (kind) {
        case LIBRARY -> "library model";
        case ANNOTATED -> "annotated as nullable";
        default -> location;
      });
      for (NullReason c = cause; c != null; c = c.cause()) {
        sb.append(" via \"").append(c.method()).append('"');
        if (c.location() != null) {
          sb.append(" at ").append(c.location());
        }
      }
      return sb.toString();
    }
  }

  private final Map<String, ClassFacts> classes;
  private final Map<String, List<String>> subtypes = new HashMap<>();
  private final Hierarchy hierarchy;
  private final LibraryModels models;
  private final int maxTargets;
  private final Thresholds thresholds;
  private final Map<String, Optional<NullReason>> memo = new HashMap<>();
  private final Map<String, Integer> callSites = new HashMap<>();
  private final Map<String, Integer> nullChecks = new HashMap<>();

  /**
   * When a null return comes from project code (not a library model), the result must be null-checked at
   * {@code minRatio} of at least {@code minSites} call sites before an unchecked dereference is reported.
   */
  public record Thresholds(int maxTargets, int minSites, double minRatio) {
  }

  /** Project-wide counts for one callee. */
  public record CallStats(int sites, int checked) {
  }

  public Summaries(Collection<ClassFacts> facts, Hierarchy hierarchy, LibraryModels models, Thresholds thresholds) {
    this.classes = byName(facts);
    for (ClassFacts c : facts) {
      if (c.superName() != null) {
        subtypes.computeIfAbsent(c.superName(), k -> new ArrayList<>()).add(c.name());
      }
      c.interfaces().forEach(i -> subtypes.computeIfAbsent(i, k -> new ArrayList<>()).add(c.name()));
      for (MethodFacts m : c.methods().values()) {
        m.callSites().forEach((k, n) -> callSites.merge(k, n, Integer::sum));
        m.nullChecks().forEach((k, n) -> nullChecks.merge(k, n, Integer::sum));
      }
    }
    this.hierarchy = hierarchy;
    this.models = models;
    this.maxTargets = thresholds.maxTargets();
    this.thresholds = thresholds;
  }

  public CallStats callStats(String callKey) {
    String k = BytecodeScanner.statKey(callKey);
    return new CallStats(callSites.getOrDefault(k, 0), nullChecks.getOrDefault(k, 0));
  }

  /**
   * Whether a dereference of the call's result should be reported: always for library-rooted reasons, and for
   * project-rooted ones only when most call sites check the result.
   */
  public boolean isReportable(String callKey, NullReason reason) {
    if (reason.fromLibrary()) {
      return true;
    }
    CallStats s = callStats(callKey);
    return s.sites() >= thresholds.minSites() && s.checked() >= thresholds.minRatio() * s.sites();
  }

  public static Map<String, ClassFacts> byName(Collection<ClassFacts> facts) {
    Map<String, ClassFacts> m = new HashMap<>();
    facts.forEach(c -> m.put(c.name(), c));
    return m;
  }

  /** @param callKey see {@link BytecodeScanner#callKey} */
  public synchronized Optional<NullReason> nullReturn(String callKey) {
    Optional<NullReason> r = memo.get(callKey);
    if (r != null) {
      return r;
    }
    // ponytail: recursion cycles resolve to "not null" (under-approximation, keeps precision); full fixpoint if recall matters
    memo.put(callKey, Optional.empty());
    r = Optional.ofNullable(compute(callKey));
    memo.put(callKey, r);
    return r;
  }

  private NullReason compute(String callKey) {
    boolean knownLookup = callKey.startsWith(BytecodeScanner.KNOWN_LOOKUP);
    if (knownLookup) {
      callKey = callKey.substring(BytecodeScanner.KNOWN_LOOKUP.length());
    }
    boolean exact = callKey.startsWith("!");
    String key = exact ? callKey.substring(1) : callKey;
    int hash = key.indexOf('#');
    int paren = key.indexOf('(', hash);
    String owner = key.substring(0, hash);
    String name = key.substring(hash + 1, paren);
    String desc = key.substring(paren);
    String nameDesc = name + desc;

    LibraryModels.Match lib = models.nullableReturn(owner, name, desc, hierarchy);
    if (lib != null) {
      if (knownLookup && !lib.guards().isEmpty()) {
        // constant table, or a get(key) wrapper whose caller chooses the key: content is known to the author
        return null;
      }
      return new NullReason(display(owner, name), null, null, NullReason.Kind.LIBRARY, lib.guards());
    }

    List<ClassFacts> targets = targets(owner, nameDesc, exact);
    if (targets.size() > maxTargets) {
      return null;
    }
    for (ClassFacts c : targets) {
      MethodFacts mf = c.methods().get(nameDesc);
      if (!exact && mf.onlyNull() && targets.size() > 1) {
        // a "return null" default that subclasses override: callers rely on the override
        continue;
      }
      String display = display(c.name(), name);
      Integer line = reachableNullReturn(mf);
      if (line != null) {
        return new NullReason(display, location(c, line), null, NullReason.Kind.RETURNS_NULL, Set.of());
      }
      if (mf.annotatedNullable()) {
        return new NullReason(display, null, null, NullReason.Kind.ANNOTATED, Set.of());
      }
      for (Map.Entry<String, Integer> call : sorted(mf.nullableCalls())) {
        Optional<NullReason> cause = nullReturn(call.getKey());
        if (cause.isPresent() && Collections.disjoint(cause.get().guards(), mf.invokedNames())) {
          return new NullReason(display, location(c, call.getValue()), cause.get(), NullReason.Kind.RETURNS_CALL, Set.of());
        }
      }
    }
    return null;
  }

  private List<ClassFacts> targets(String owner, String nameDesc, boolean exact) {
    List<ClassFacts> targets = new ArrayList<>();
    ClassFacts declaring = declaring(owner, nameDesc);
    if (declaring != null) {
      targets.add(declaring);
    }
    // only project types: a call on Map.Entry is mostly a JDK entry, not the project's own implementation
    if (!exact && classes.containsKey(owner)) {
      collectOverrides(owner, nameDesc, targets);
    }
    return targets;
  }

  /** First line of a {@code return null} that is not only reached after calls that always throw. */
  private Integer reachableNullReturn(MethodFacts mf) {
    Integer best = null;
    for (Map.Entry<Integer, Set<String>> r : mf.nullReturns().entrySet()) {
      boolean reachable = r.getValue().isEmpty() || !r.getValue().stream().allMatch(this::neverReturns);
      if (reachable && (best == null || r.getKey() < best)) {
        best = r.getKey();
      }
    }
    return best;
  }

  /** Every project implementation of the call always throws ({@code reportError(...); return null;}). */
  private boolean neverReturns(String callKey) {
    boolean exact = callKey.startsWith("!");
    String key = exact ? callKey.substring(1) : callKey;
    int hash = key.indexOf('#');
    String nameDesc = key.substring(hash + 1);
    List<ClassFacts> targets = targets(key.substring(0, hash), nameDesc, exact);
    return !targets.isEmpty() && targets.stream().noneMatch(c -> c.methods().get(nameDesc).returnsNormally());
  }

  /** The class declaring {@code nameDesc}, searching {@code owner} and its project superclasses. */
  private ClassFacts declaring(String owner, String nameDesc) {
    for (ClassFacts c = classes.get(owner); c != null; c = classes.get(c.superName())) {
      if (c.methods().containsKey(nameDesc)) {
        return c;
      }
      if (c.superName() == null) {
        break;
      }
    }
    return null;
  }

  private void collectOverrides(String type, String nameDesc, List<ClassFacts> out) {
    for (String sub : subtypes.getOrDefault(type, List.of())) {
      if (out.size() > maxTargets) {
        return;
      }
      ClassFacts c = classes.get(sub);
      if (c.methods().containsKey(nameDesc) && !out.contains(c)) {
        out.add(c);
      }
      collectOverrides(sub, nameDesc, out);
    }
  }

  private static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> calls) {
    List<Map.Entry<String, Integer>> l = new ArrayList<>(calls.entrySet());
    l.sort(Map.Entry.comparingByValue());
    return l;
  }

  private static String location(ClassFacts c, int line) {
    String file = c.sourceFile() != null ? c.sourceFile() : simpleName(c.name());
    return line > 0 ? file + ":" + line : file;
  }

  private static String display(String owner, String name) {
    return simpleName(owner) + "." + name + "()";
  }

  private static String simpleName(String internalName) {
    return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '.');
  }
}
