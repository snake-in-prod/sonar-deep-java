package io.github.snakeinprod.deepjava.prepass;

import io.github.snakeinprod.deepjava.prepass.ClassFacts.CallSite;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.FieldAccess;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.FieldFacts;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.LockEdge;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.LockedCall;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.MethodFacts;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.ThrowSite;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.TryFacts;
import io.github.snakeinprod.deepjava.prepass.Summaries.Finding;
import io.github.snakeinprod.deepjava.prepass.Summaries.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;

/** Rules computed entirely from the project's bytecode; each finding is reported by the rule's check on its file. */
final class ProjectAnalyses implements Opcodes {

  // keys must match the @Rule keys of the checks
  static final String IGNORED_RESULT = "IgnoredResultStatistical";
  static final String MISSING_SUPER = "MissingSuperCall";
  static final String INCONSISTENT_LOCKING = "InconsistentLocking";
  static final String GUARDED_BY = "GuardedByViolation";
  static final String MIXED_LOCKS = "MixedLocks";
  static final String LOCK_NOT_RELEASED = "LockNotReleasedAcrossMethods";
  static final String NON_REENTRANT = "NonReentrantLockReacquired";
  static final String STATIC_INSTANCE_LOCK = "StaticFieldInstanceLock";
  static final String LOCK_ORDER = "LockOrderCycle";
  static final String UNCHECKED_ENTRY = "UncheckedExceptionInEntryPoint";
  static final String UNREACHABLE_CATCH = "UnreachableCatch";
  static final String CODE_AFTER_THROW = "CodeAfterAlwaysThrows";
  static final String DEAD_ENUM_CASE = "DeadEnumCase";
  static final String EMPTY_MAP = "EmptyMapLookup";
  static final String LOMBOK_UNREACHABLE = "LombokUnreachableCode";

  private static final Set<String> ENTRY_ANNOTATIONS = Set.of("Scheduled", "Async", "KafkaListener", "RabbitListener",
    "JmsListener", "EventListener", "SqsListener", "StreamListener");
  private static final Set<String> MAP_TYPES = Set.of("Ljava/util/Map;", "Ljava/util/HashMap;", "Ljava/util/LinkedHashMap;",
    "Ljava/util/TreeMap;", "Ljava/util/concurrent/ConcurrentHashMap;", "Ljava/util/concurrent/ConcurrentMap;", "Ljava/util/Hashtable;");
  private static final Set<String> MAP_MUTATORS = Set.of("put", "putAll", "putIfAbsent", "compute", "computeIfAbsent",
    "computeIfPresent", "merge", "replace", "replaceAll");

  private final Summaries s;
  private final Summaries.Thresholds t;
  private final List<Method> methods = new ArrayList<>();
  private final Map<String, Finding> out = new LinkedHashMap<>();
  private final Set<Method> lockContext = new HashSet<>();

  ProjectAnalyses(Summaries s) {
    this.s = s;
    this.t = s.thresholds();
    for (ClassFacts c : s.classes().values()) {
      if (isGenerated(c.annotations())) {
        continue;
      }
      c.methods().forEach((nd, f) -> {
        if (!isGenerated(f.info().annotations())) {
          methods.add(new Method(c, nd, f));
        }
      });
    }
  }

  /** Lombok, annotation-processor and Avro output: its findings belong to the generator, not the project. */
  static boolean isGenerated(Map<String, String> annotations) {
    return annotations.containsKey("Generated") || annotations.containsKey("AvroGenerated");
  }

  List<Finding> run() {
    List<Finding> all = new ArrayList<>(new TaintSolver(s).run());
    computeLockContext();
    ignoredResults();
    missingSuper();
    locking();
    lockNotReleased();
    nonReentrant();
    lockOrder();
    uncheckedInEntryPoints();
    unreachableCatch();
    codeAfterThrow();
    deadEnumCases();
    emptyMaps();
    if (s.options().referenceParity()) {
      lombokUnreachable();
    }
    all.addAll(out.values());
    return all;
  }

  /**
   * Generated members (Lombok included, which the other analyses skip) that test a {@code @NonNull} value for null:
   * the null branch cannot run. The check keeps only lines that carry the Lombok annotation.
   */
  private void lombokUnreachable() {
    for (ClassFacts c : s.classes().values()) {
      c.methods().forEach((nd, f) -> f.uses().nonNullChecks().forEach((line, name) -> add(LOMBOK_UNREACHABLE, c, line,
        "The generated \"" + displayMember(c, nd) + "\" tests the @NonNull \"" + name + "\" for null: that branch is unreachable.")));
    }
  }

  private static String displayMember(ClassFacts c, String nameDesc) {
    String name = nameDesc.substring(0, nameDesc.indexOf('('));
    return "<init>".equals(name) ? Summaries.simpleName(c.name()) + "()" : name + "()";
  }

  private void add(String rule, ClassFacts c, int line, String message) {
    if (line > 0) {
      out.putIfAbsent(rule + "|" + c.sourcePath() + "|" + line, new Finding(rule, c.sourcePath(), line, message));
    }
  }

  private boolean atLeast(int part, int total) {
    return total >= t.minSites() && part >= t.minRatio() * total;
  }

  // ------------------------------------------------------------------ 5a ignored results

  private static final java.util.regex.Pattern PURE_JDK =
    java.util.regex.Pattern.compile("java/lang/(Integer|Long|Short|Byte|Double|Float|Boolean|String)|java/math/Big\\w+|java/time/\\w+|java/util/UUID");

  private void ignoredResults() {
    Map<String, Integer> used = new HashMap<>();
    Map<String, List<Object[]>> ignored = new HashMap<>();
    for (Method m : methods) {
      m.facts().uses().valueSites().forEach((k, n) -> used.merge(k, n, Integer::sum));
      m.facts().uses().ignoredResults().forEach((k, lines) -> lines.forEach(l -> ignored.computeIfAbsent(k, x -> new ArrayList<>()).add(new Object[] {m.owner(), l})));
    }
    ignored.forEach((k, sites) -> {
      Summaries.Call c = Summaries.parse(k);
      // project methods and side-effect-free JDK values (Integer.parseInt() called only to validate); other JDK
      // results such as Iterator.next() are often skipped on purpose
      boolean pureJdk = PURE_JDK.matcher(c.owner()).matches() && !c.desc().endsWith(")V");
      if (!pureJdk && (c.desc().endsWith(")L" + c.owner() + ";") || c.desc().endsWith(")Ljava/lang/Object;") || s.targets(k).isEmpty()
        || s.neverReturns(k))) {
        return;
      }
      int u = used.getOrDefault(k, 0);
      int total = u + sites.size();
      if (atLeast(u, total)) {
        for (Object[] site : sites) {
          add(IGNORED_RESULT, (ClassFacts) site[0], (Integer) site[1], "The result of \"" + Summaries.display(c.owner(), c.name())
            + "\" is used at " + u + " of " + total + " call sites; check it here too.");
        }
      }
    });
  }

  // ------------------------------------------------------------------ 5c missing super call

  private void missingSuper() {
    Map<String, List<Method>> overridesByBase = new HashMap<>();
    for (Method m : methods) {
      int a = m.facts().info().access();
      if ((a & (ACC_STATIC | ACC_PRIVATE | ACC_BRIDGE | ACC_SYNTHETIC)) != 0 || m.name().startsWith("<")
        || Set.of("toString()Ljava/lang/String;", "hashCode()I", "equals(Ljava/lang/Object;)Z").contains(m.nameDesc())) {
        continue;
      }
      for (ClassFacts sup = superOf(m.owner()); sup != null; sup = superOf(sup)) {
        MethodFacts base = sup.methods().get(m.nameDesc());
        if (base != null) {
          if ((base.info().access() & (ACC_ABSTRACT | ACC_PRIVATE)) == 0) {
            overridesByBase.computeIfAbsent(sup.name() + "#" + m.nameDesc(), k -> new ArrayList<>()).add(m);
          }
          break;
        }
      }
    }
    overridesByBase.forEach((base, overrides) -> {
      long calling = overrides.stream().filter(m -> m.facts().uses().callsSuper()).count();
      if (atLeast((int) calling, overrides.size()) && calling < overrides.size()) {
        Summaries.Call c = Summaries.parse(base + "");
        String name = c.owner().substring(c.owner().lastIndexOf('/') + 1) + "." + c.name() + "()";
        for (Method m : overrides) {
          if (!m.facts().uses().callsSuper()) {
            add(MISSING_SUPER, m.owner(), m.facts().info().firstLine(),
              calling + " of " + overrides.size() + " overrides of \"" + name + "\" call super." + c.name() + "(); this one does not.");
          }
        }
      }
    });
  }

  private ClassFacts superOf(ClassFacts c) {
    return c.superName() == null ? null : s.classes().get(c.superName());
  }

  // ------------------------------------------------------------------ 5d / 6a / 6b / 6e locking

  /** Methods only ever called while a lock is held (private helpers of synchronized methods). */
  private void computeLockContext() {
    Map<String, List<Boolean>> sites = new HashMap<>();
    for (Method m : methods) {
      Set<String> locked = new HashSet<>();
      m.facts().locks().lockedCalls().forEach(lc -> locked.add(lc.callKey() + "@" + lc.line()));
      for (CallSite cs : m.facts().ex().calls()) {
        sites.computeIfAbsent(BytecodeScanner.statKey(cs.callKey()), k -> new ArrayList<>()).add(locked.contains(cs.callKey() + "@" + cs.line()));
      }
    }
    for (Method m : methods) {
      List<Boolean> l = sites.get(m.owner().name() + "#" + m.nameDesc());
      if (l != null && !l.isEmpty() && l.stream().allMatch(b -> b) && (m.facts().info().access() & ACC_PUBLIC) == 0) {
        lockContext.add(m);
      }
    }
  }

  private FieldFacts field(String key) {
    int hash = key.indexOf('#');
    ClassFacts c = s.classes().get(key.substring(0, hash));
    return c == null ? null : c.fields().get(key.substring(hash + 1));
  }

  private record Access(Method m, FieldAccess a, boolean locked) {
  }

  private void locking() {
    Map<String, List<Access>> byField = new HashMap<>();
    for (Method m : methods) {
      for (FieldAccess a : m.facts().locks().accesses()) {
        FieldAccess c = new FieldAccess(a.field(), a.write(), a.line(), canonical(a.locks()));
        byField.computeIfAbsent(a.field(), k -> new ArrayList<>()).add(new Access(m, c, !a.locks().isEmpty() || lockContext.contains(m)));
      }
    }
    byField.forEach((key, accesses) -> {
      FieldFacts f = field(key);
      if (f == null || (f.access() & (ACC_FINAL | ACC_VOLATILE | ACC_SYNTHETIC)) != 0) {
        return;
      }
      String name = key.substring(key.indexOf('#') + 1);
      // 6a @GuardedBy
      String guard = f.annotations().get("GuardedBy");
      if (guard != null) {
        for (Access a : accesses) {
          if (!lockContext.contains(a.m()) && !holds(a.a().locks(), guard, key)) {
            add(GUARDED_BY, a.m().owner(), a.a().line(), "\"" + name + "\" is annotated @GuardedBy(\"" + guard + "\") but accessed without that lock.");
          }
        }
      }
      // 5d inconsistent locking
      long locked = accesses.stream().filter(Access::locked).count();
      if (guard == null && atLeast((int) locked, accesses.size()) && locked < accesses.size()) {
        for (Access a : accesses) {
          if (!a.locked()) {
            add(INCONSISTENT_LOCKING, a.m().owner(), a.a().line(), "\"" + name + "\" is accessed under a lock at " + locked + " of "
              + accesses.size() + " places; hold the lock here too.");
          }
        }
      }
      // 6b mixed locks
      Map<String, List<Access>> byLock = new HashMap<>();
      for (Access a : accesses) {
        if (a.a().locks().size() == 1) {
          byLock.computeIfAbsent(a.a().locks().iterator().next(), k -> new ArrayList<>()).add(a);
        }
      }
      boolean anyBoth = accesses.stream().anyMatch(a -> a.a().locks().size() > 1);
      if (byLock.size() >= 2 && !anyBoth) {
        List<Map.Entry<String, List<Access>>> groups = new ArrayList<>(byLock.entrySet());
        groups.sort((x, y) -> x.getValue().size() != y.getValue().size() ? y.getValue().size() - x.getValue().size() : x.getKey().compareTo(y.getKey()));
        Map.Entry<String, List<Access>> main = groups.get(0);
        if (main.getValue().size() >= 2) {
          Access ref = main.getValue().get(0);
          for (int g = 1; g < groups.size(); g++) {
            if (groups.get(g).getValue().size() >= 2) {
              for (Access a : groups.get(g).getValue()) {
                add(MIXED_LOCKS, a.m().owner(), a.a().line(), "\"" + name + "\" is protected by " + lockName(groups.get(g).getKey())
                  + " here but by " + lockName(main.getKey()) + " at " + ref.m().location(ref.a().line()) + ".");
              }
            }
          }
        }
      }
      // 6e static field under an instance lock
      if ((f.access() & ACC_STATIC) != 0) {
        for (Access a : accesses) {
          Set<String> held = a.a().locks();
          if (a.a().write() && !held.isEmpty() && held.stream().allMatch(this::isInstanceLock)) {
            add(STATIC_INSTANCE_LOCK, a.m().owner(), a.a().line(), "Static \"" + name + "\" is written while holding only "
              + lockName(held.iterator().next()) + "; other instances do not exclude this write.");
          }
        }
      }
    });
  }

  private boolean holds(Set<String> held, String guard, String fieldKey) {
    String owner = fieldKey.substring(0, fieldKey.indexOf('#'));
    for (String h : held) {
      if (guard.equals("this") && h.equals(canonical("this:" + owner))) {
        return true;
      }
      if (guard.endsWith(".class") && h.startsWith("class:")) {
        return true;
      }
      if (h.endsWith("#" + guard) || h.endsWith("#" + guard + ".readLock") || h.endsWith("#" + guard + ".writeLock")) {
        return true;
      }
    }
    return false;
  }

  private boolean isInstanceLock(String id) {
    if (id.startsWith("this:")) {
      return true;
    }
    if (id.startsWith("class:") || id.startsWith("static:")) {
      return false;
    }
    String key = (id.startsWith("lock:") ? id.substring(5) : id.substring(id.indexOf(':') + 1)).replaceAll("\\.(readLock|writeLock)$", "");
    FieldFacts f = key.contains("#") ? field(key) : null;
    return f != null && (f.access() & ACC_STATIC) == 0;
  }

  /** {@code this:Sub} and {@code this:Base} are the same object: use the top project superclass. */
  private String canonical(String id) {
    if (!id.startsWith("this:")) {
      return id;
    }
    ClassFacts c = s.classes().get(id.substring(5));
    String top = id.substring(5);
    for (; c != null; c = superOf(c)) {
      top = c.name();
    }
    return "this:" + top;
  }

  private Set<String> canonical(Set<String> ids) {
    Set<String> out = new HashSet<>();
    ids.forEach(i -> out.add(canonical(i)));
    return out;
  }

  private static String lockName(String id) {
    if (id.startsWith("this:")) {
      return "\"" + Summaries.simpleName(id.substring(5)) + ".this\"";
    }
    if (id.startsWith("class:")) {
      return "\"" + Summaries.simpleName(id.substring(6)) + ".class\"";
    }
    String k = id.replaceFirst("^lock:", "").replaceFirst("^(field|static):", "");
    return "\"" + k.substring(k.indexOf('#') + 1) + "\"";
  }

  // ------------------------------------------------------------------ 6d lock held across methods

  private void lockNotReleased() {
    Set<Method> helpers = new HashSet<>();
    for (Method m : methods) {
      if (!m.facts().locks().heldAtReturn().isEmpty()) {
        helpers.add(m);
      }
    }
    if (helpers.isEmpty()) {
      return;
    }
    for (Method m : methods) {
      if (helpers.contains(m) || m.facts().nulls().invokedNames().stream().anyMatch(n -> n.startsWith("unlock"))) {
        continue;
      }
      for (CallSite cs : m.facts().ex().calls()) {
        List<Method> targets = s.targets(cs.callKey());
        if (!targets.isEmpty() && helpers.containsAll(targets)) {
          Method h = targets.get(0);
          add(LOCK_NOT_RELEASED, m.owner(), cs.line(), "\"" + h.display() + "\" returns holding "
            + lockName(h.facts().locks().heldAtReturn().iterator().next()) + ", and this method never releases it.");
        }
      }
    }
  }

  // ------------------------------------------------------------------ 6d non-reentrant locks, 6f lock order

  private final Map<Method, Set<String>> acquiredClosure = new HashMap<>();

  private Set<String> acquired(Method m, int depth) {
    Set<String> r = acquiredClosure.get(m);
    if (r != null) {
      return r;
    }
    acquiredClosure.put(m, Set.of());
    Set<String> out = new HashSet<>(m.facts().locks().acquired().keySet());
    if (depth < 6) {
      for (CallSite cs : m.facts().ex().calls()) {
        List<Method> targets = s.targets(cs.callKey());
        if (targets.size() <= t.maxTargets() || s.options().referenceParity()) {
          targets.forEach(tm -> out.addAll(acquired(tm, depth + 1)));
        }
      }
    }
    acquiredClosure.put(m, Set.copyOf(out));
    return acquiredClosure.get(m);
  }

  private boolean isStamped(String lockId) {
    String key = lockId.replaceFirst("^lock:", "");
    FieldFacts f = key.contains("#") ? field(key) : null;
    return f != null && f.desc().equals("Ljava/util/concurrent/locks/StampedLock;");
  }

  private void nonReentrant() {
    for (Method m : methods) {
      m.facts().locks().reacquired().forEach((id, line) ->
        add(NON_REENTRANT, m.owner(), line, lockName(id) + " is a StampedLock and is already held here; this blocks the thread."));
      for (LockedCall lc : m.facts().locks().lockedCalls()) {
        for (String held : lc.locks()) {
          if (held.startsWith("lock:") && isStamped(held)) {
            for (Method target : s.targets(lc.callKey())) {
              if (acquired(target, 0).contains(held)) {
                add(NON_REENTRANT, m.owner(), lc.line(), "\"" + target.display() + "\" acquires StampedLock " + lockName(held)
                  + ", which is already held here; this blocks the thread.");
              }
            }
          }
        }
      }
    }
  }

  private record Site(ClassFacts c, int line) {
  }

  private void lockOrder() {
    Map<String, Site> graph = new LinkedHashMap<>();
    for (Method m : methods) {
      for (LockEdge e : m.facts().locks().edges()) {
        String held = canonical(e.held());
        String acq = canonical(e.acquired());
        if (!held.equals(acq)) {
          graph.putIfAbsent(held + "->" + acq, new Site(m.owner(), e.line()));
        }
      }
      for (LockedCall lc : m.facts().locks().lockedCalls()) {
        List<Method> targets = s.targets(lc.callKey());
        if (targets.size() > t.maxTargets() && !s.options().referenceParity()) {
          continue;
        }
        for (Method target : targets) {
          Set<String> heldNow = canonical(lc.locks());
          for (String acq : canonical(acquired(target, 0))) {
            if (heldNow.contains(acq)) {
              // re-entering a monitor that is already held adds no new order
              continue;
            }
            for (String held : heldNow) {
              if (!held.equals(acq)) {
                graph.putIfAbsent(held + "->" + acq, new Site(m.owner(), lc.line()));
              }
            }
          }
        }
      }
    }
    // ponytail: only two-lock cycles; longer cycles need a full cycle search
    graph.forEach((edge, site) -> {
      String[] ab = edge.split("->");
      Site reverse = graph.get(ab[1] + "->" + ab[0]);
      if (reverse != null && ab[0].compareTo(ab[1]) < 0) {
        add(LOCK_ORDER, site.c(), site.line(), lockName(ab[1]) + " is acquired while holding " + lockName(ab[0])
          + " here, and in the opposite order at " + Summaries.location(reverse.c(), reverse.line()) + "; this can deadlock.");
      }
    });
  }

  // ------------------------------------------------------------------ 7a unchecked exceptions in entry points

  private final Map<Method, Map<String, String>> escaping = new HashMap<>();

  private boolean caught(Set<String> handlers, String type) {
    if (handlers.isEmpty()) {
      return false;
    }
    Set<String> supers = s.hierarchy().selfAndSupertypes(type);
    return handlers.stream().anyMatch(supers::contains);
  }

  /** Unchecked exceptions from library input checks (Integer.parseInt...) that can leave {@code m}, with how. */
  private Map<String, String> escaping(Method m, int depth) {
    Map<String, String> r = escaping.get(m);
    if (r != null) {
      return r;
    }
    escaping.put(m, Map.of());
    Map<String, String> out = new LinkedHashMap<>();
    for (CallSite cs : m.facts().ex().calls()) {
      if (isLambdaRef(cs)) {
        continue;
      }
      Summaries.Call c = Summaries.parse(cs.callKey());
      String ex = s.models().uncheckedThrow(c.owner(), c.name(), c.desc());
      if (ex != null && ex.equals("java/util/NoSuchElementException") && !s.options().referenceParity()
        && (depth > 0 || "next".equals(c.name()) && s.hierarchy().selfAndSupertypes(c.owner()).contains("java/util/Iterator"))) {
        // Optional.get() deep in a callee is mostly guarded there (isPresent); Iterator.next() mostly by a for-each's hasNext()
        ex = null;
      }
      if (ex != null && !caught(cs.caught(), ex)) {
        out.putIfAbsent(ex, "\"" + Summaries.display(c.owner(), c.name()) + "\" at " + m.location(cs.line()));
      }
      boolean parity = s.options().referenceParity();
      if (depth < (parity ? 10 : 6)) {
        List<Method> targets = s.targets(cs.callKey());
        if (targets.size() <= (parity ? t.maxTargets() : 2)) {
          for (Method tm : targets) {
            escaping(tm, depth + 1).forEach((e, how) -> {
              if (!caught(cs.caught(), e)) {
                out.putIfAbsent(e, how);
              }
            });
          }
        }
      }
    }
    escaping.put(m, Map.copyOf(out));
    return escaping.get(m);
  }

  /** A lambda body referenced (not called) where the lambda is created. */
  private static boolean isLambdaRef(CallSite cs) {
    return cs.nextLine() == -1 && cs.callKey().contains("#lambda$");
  }

  private boolean isEntryPoint(Method m) {
    if (m.facts().info().annotations().keySet().stream().anyMatch(ENTRY_ANNOTATIONS::contains)) {
      return true;
    }
    Set<String> supers = s.hierarchy().selfAndSupertypes(m.owner().name());
    return m.nameDesc().equals("run()V") && (supers.contains("java/lang/Runnable") || supers.contains("java/lang/Thread"))
      || m.nameDesc().equals("call()Ljava/lang/Object;") && supers.contains("java/util/concurrent/Callable")
      || m.nameDesc().equals("main([Ljava/lang/String;)V") && (m.facts().info().access() & ACC_STATIC) != 0;
  }

  private void uncheckedInEntryPoints() {
    for (Method m : methods) {
      if ((m.facts().info().access() & ACC_SYNTHETIC) != 0 || !isEntryPoint(m)) {
        continue;
      }
      Map<String, String> ex = escaping(m, 0);
      if (!ex.isEmpty()) {
        Map.Entry<String, String> first = ex.entrySet().iterator().next();
        String what = m.name().equals("main") ? "the program stops" : "the thread or task stops and the work is lost";
        add(UNCHECKED_ENTRY, m.owner(), m.facts().info().firstLine(), "\"" + Summaries.simpleName(first.getKey()) + "\" from "
          + first.getValue() + " can escape \"" + m.display() + "\": " + what + ". Catch it.");
      }
    }
  }

  // ------------------------------------------------------------------ 7b unreachable catch

  private final Map<Method, Set<String>> thrown = new HashMap<>();

  /** Exception types created and thrown by {@code m} or its callees and not caught on the way ({@code ?}: unknown). */
  private Set<String> thrown(Method m, int depth) {
    Set<String> r = thrown.get(m);
    if (r != null) {
      return r;
    }
    thrown.put(m, Set.of());
    Set<String> out = new HashSet<>();
    for (ThrowSite ts : m.facts().ex().throwsNew()) {
      if (ts.type().equals("?") || !caught(ts.caught(), ts.type())) {
        out.add(ts.type());
      }
    }
    for (CallSite cs : m.facts().ex().calls()) {
      if (isLambdaRef(cs)) {
        continue;
      }
      List<Method> targets = s.targets(cs.callKey());
      Summaries.Call c = Summaries.parse(cs.callKey());
      if (targets.isEmpty() && s.classes().containsKey(c.owner())) {
        out.add("?");
      }
      if (depth < 8) {
        for (Method tm : targets) {
          for (String e : thrown(tm, depth + 1)) {
            if (e.equals("?") || !caught(cs.caught(), e)) {
              out.add(e);
            }
          }
        }
      } else if (!targets.isEmpty()) {
        out.add("?");
      }
    }
    thrown.put(m, Set.copyOf(out));
    return thrown.get(m);
  }

  private void unreachableCatch() {
    for (Method m : methods) {
      for (TryFacts tf : m.facts().ex().tries()) {
        String e = tf.handlerType();
        if (tf.hasLambda() || !s.classes().containsKey(e) || !s.isSubtype(e, "java/lang/RuntimeException")) {
          continue;
        }
        if (!canThrow(m, tf, e)) {
          add(UNREACHABLE_CATCH, m.owner(), tf.line(), "Nothing in the try block throws \"" + Summaries.simpleName(e)
            + "\" or a subclass, so this catch never runs.");
        }
      }
    }
  }

  private boolean canThrow(Method m, TryFacts tf, String e) {
    for (ThrowSite ts : m.facts().ex().throwsNew()) {
      if (ts.caught().contains(e) && (ts.type().equals("?") || s.isSubtype(ts.type(), e))) {
        return true;
      }
    }
    for (String key : tf.callKeys()) {
      Summaries.Call c = Summaries.parse(key);
      List<Method> targets = s.targets(key);
      if (targets.isEmpty()) {
        // a library cannot throw a project exception, unless it calls back into project code
        if (s.classes().containsKey(c.owner()) || c.desc().contains("Ljava/util/function/") || c.desc().contains("Ljava/lang/Runnable;")
          || c.desc().contains("Ljava/util/concurrent/Callable;") || c.desc().contains("Ljava/lang/Object;")) {
          return true;
        }
        continue;
      }
      // abstract targets: implementations may be lambdas the analysis cannot see
      if (targets.size() > t.maxTargets() || targets.stream().anyMatch(x -> (x.facts().info().access() & ACC_ABSTRACT) != 0)) {
        return true;
      }
      for (Method tm : targets) {
        for (String x : thrown(tm, 0)) {
          if (x.equals("?") || s.isSubtype(x, e)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  // ------------------------------------------------------------------ 7c code after a call that always throws

  private void codeAfterThrow() {
    for (Method m : methods) {
      for (CallSite cs : m.facts().ex().calls()) {
        if (!cs.nextIsTrivial() && cs.nextLine() > 0 && s.neverReturns(cs.callKey())) {
          Summaries.Call c = Summaries.parse(cs.callKey());
          add(CODE_AFTER_THROW, m.owner(), cs.nextLine(), "\"" + Summaries.display(c.owner(), c.name())
            + "\" always throws, so this code never runs.");
        }
      }
    }
  }

  // ------------------------------------------------------------------ 8 dead enum cases, empty maps

  private void deadEnumCases() {
    Set<String> loads = new HashSet<>();
    Set<String> reflective = new HashSet<>();
    boolean frameworks = false;
    for (Method m : methods) {
      loads.addAll(m.facts().uses().enumLoads());
      boolean switchMapClass = m.owner().fields().keySet().stream().anyMatch(f -> f.startsWith("$SwitchMap$"));
      boolean enumSynthetic = (m.owner().access() & ACC_ENUM) != 0 && Set.of("values", "valueOf", "$values", "<clinit>", "<init>").contains(m.name());
      if (switchMapClass || enumSynthetic) {
        continue;
      }
      for (CallSite cs : m.facts().ex().calls()) {
        Summaries.Call c = Summaries.parse(cs.callKey());
        if (c.name().equals("values") || c.name().equals("valueOf")) {
          reflective.add(c.owner());
        }
        String o = c.owner();
        if (o.equals("java/lang/Enum") || o.equals("java/util/EnumSet") || o.equals("java/lang/Class") && c.name().equals("getEnumConstants")
          || o.startsWith("com/fasterxml/jackson/") || o.startsWith("com/google/gson/") || o.startsWith("javax/persistence/")
          || o.startsWith("jakarta/persistence/") || o.startsWith("org/hibernate/") || o.startsWith("org/springframework/")
          || o.startsWith("jakarta/ws/") || o.startsWith("javax/ws/") || o.startsWith("org/apache/commons/lang3/EnumUtils")) {
          frameworks = true;
        }
      }
    }
    if (frameworks) {
      return;
    }
    for (Method m : methods) {
      m.facts().uses().switchMapLoads().forEach((constant, line) -> {
        String owner = constant.substring(0, constant.indexOf('#'));
        ClassFacts e = s.classes().get(owner);
        if (e != null && (e.access() & ACC_ENUM) != 0 && !reflective.contains(owner) && !loads.contains(constant)) {
          add(DEAD_ENUM_CASE, m.owner(), line, "\"" + Summaries.simpleName(owner) + "." + constant.substring(constant.indexOf('#') + 1)
            + "\" is never used outside switch statements, so this case never matches.");
        }
      });
    }
  }

  private void emptyMaps() {
    Map<String, String> inits = new HashMap<>();
    Set<String> writes = new HashSet<>();
    Set<String> escapes = new HashSet<>();
    Map<String, Set<String>> calls = new HashMap<>();
    Map<String, List<Object[]>> lookups = new HashMap<>();
    for (Method m : methods) {
      m.facts().uses().fieldInits().forEach((k, v) -> inits.merge(k, v, (a, b) -> a.equals(b) ? a : "?"));
      writes.addAll(m.facts().uses().fieldWrites());
      escapes.addAll(m.facts().uses().fieldEscapes());
      m.facts().uses().fieldCalls().forEach((k, v) -> {
        calls.computeIfAbsent(k, x -> new HashSet<>()).addAll(v);
        for (String call : v) {
          String name = call.substring(0, call.indexOf('@'));
          if (Set.of("containsKey", "get", "containsValue", "isEmpty").contains(name)) {
            lookups.computeIfAbsent(k, x -> new ArrayList<>()).add(new Object[] {m.owner(), Integer.parseInt(call.substring(call.indexOf('@') + 1)), name});
          }
        }
      });
    }
    lookups.forEach((key, sites) -> {
      FieldFacts f = field(key);
      String init = inits.get(key);
      if (f == null || (f.access() & ACC_PRIVATE) == 0 || !MAP_TYPES.contains(f.desc()) || init == null || !init.startsWith("empty:")
        || writes.contains(key) || escapes.contains(key)) {
        return;
      }
      boolean mutated = calls.getOrDefault(key, Set.of()).stream().anyMatch(c -> MAP_MUTATORS.contains(c.substring(0, c.indexOf('@'))));
      if (!mutated) {
        String name = key.substring(key.indexOf('#') + 1);
        for (Object[] site : sites) {
          String what = switch ((String) site[2]) {
            case "get" -> "get() always returns null";
            case "isEmpty" -> "isEmpty() is always true";
            default -> site[2] + "() is always false";
          };
          add(EMPTY_MAP, (ClassFacts) site[0], (Integer) site[1], "\"" + name + "\" is created empty and never filled, so " + what + ".");
        }
      }
    });
  }
}
