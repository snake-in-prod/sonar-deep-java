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
  private final Map<String, Optional<NullReason>> memo = new HashMap<>();

  public Summaries(Collection<ClassFacts> facts, Hierarchy hierarchy, LibraryModels models, int maxTargets) {
    this.classes = byName(facts);
    for (ClassFacts c : facts) {
      if (c.superName() != null) {
        subtypes.computeIfAbsent(c.superName(), k -> new ArrayList<>()).add(c.name());
      }
      c.interfaces().forEach(i -> subtypes.computeIfAbsent(i, k -> new ArrayList<>()).add(c.name()));
    }
    this.hierarchy = hierarchy;
    this.models = models;
    this.maxTargets = maxTargets;
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
      return new NullReason(display(owner, name), null, null, NullReason.Kind.LIBRARY, lib.guards());
    }

    List<ClassFacts> targets = new ArrayList<>();
    ClassFacts declaring = declaring(owner, nameDesc);
    if (declaring != null) {
      targets.add(declaring);
    }
    if (!exact) {
      collectOverrides(owner, nameDesc, targets);
    }
    if (targets.size() > maxTargets) {
      return null;
    }
    for (ClassFacts c : targets) {
      MethodFacts mf = c.methods().get(nameDesc);
      String display = display(c.name(), name);
      if (mf.nullReturnLine() >= 0) {
        return new NullReason(display, location(c, mf.nullReturnLine()), null, NullReason.Kind.RETURNS_NULL, Set.of());
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
