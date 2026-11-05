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
    public enum Kind { RETURNS_NULL, RETURNS_CALL, ANNOTATED, LIBRARY, NULLABLE_FIELD, LOCAL_NULL, COLLECTION_NULL, NULL_FOR_NULL_ARGUMENT }

    /** The null comes from a library model at the end of the chain (no call-site statistics needed). */
    public boolean fromLibrary() {
      return root().kind() == Kind.LIBRARY;
    }

    public NullReason root() {
      NullReason r = this;
      while (r.cause() != null) {
        r = r.cause();
      }
      return r;
    }

    /** The null is declared (library model, annotation) or explicit ({@code x = null; ... return x;}). */
    public boolean isStrong() {
      return root().kind() != Kind.RETURNS_NULL;
    }

    /** e.g. {@code Config.java:4 via "Map.get()"}. */
    public String detail() {
      StringBuilder sb = new StringBuilder(switch (kind) {
        case LIBRARY -> "library model";
        case ANNOTATED -> "annotated as nullable";
        case NULLABLE_FIELD -> "returns the @Nullable field " + location;
        case LOCAL_NULL -> location + " returns a local set to null";
        case COLLECTION_NULL -> location + " returns null instead of an empty collection";
        case NULL_FOR_NULL_ARGUMENT -> "generated mapper, returns null for a null argument";
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

  /**
   * @param maxTargets virtual calls with more project implementations are ignored
   * @param minSites   statistical rules need at least this many sites...
   * @param minRatio   ...and this share of them agreeing
   */
  public record Thresholds(int maxTargets, int minSites, double minRatio) {
    public static final Thresholds DEFAULT = new Thresholds(8, 5, 0.8);
  }

  /** Project-wide counts for one callee. */
  public record CallStats(int sites, int checked) {
  }

  /** An issue computed from bytecode, reported by the rule's check on the file {@code sourcePath}. */
  public record Finding(String rule, String sourcePath, int line, String message) {
  }

  /** A method with its class. */
  public record Method(ClassFacts owner, String nameDesc, MethodFacts facts) {
    public String name() {
      return nameDesc.substring(0, nameDesc.indexOf('('));
    }

    public String desc() {
      return nameDesc.substring(nameDesc.indexOf('('));
    }

    public String display() {
      return simpleName(owner.name()) + "." + name() + "()";
    }

    public String location(int line) {
      return Summaries.location(owner, line);
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Method m && m.owner.name().equals(owner.name()) && m.nameDesc.equals(nameDesc);
    }

    @Override
    public int hashCode() {
      return owner.name().hashCode() * 31 + nameDesc.hashCode();
    }
  }

  public LibraryModels models() {
    return models;
  }

  private Map<String, String> fieldInits;
  private java.util.Set<String> fieldWrites;

  /**
   * The constant a field always holds ({@code I:0}, {@code S:x}, {@code null}): set only in constructors or static
   * initialisers, always to the same constant, and never written elsewhere in the project. Null otherwise.
   */
  public synchronized String fieldConstant(String fieldKey) {
    if (fieldInits == null) {
      fieldInits = new HashMap<>();
      fieldWrites = new java.util.HashSet<>();
      for (ClassFacts c : classes.values()) {
        for (MethodFacts m : c.methods().values()) {
          m.uses().fieldInits().forEach((k, v) -> fieldInits.merge(k, v, (x, y) -> x.equals(y) ? x : "?"));
          fieldWrites.addAll(m.uses().fieldWrites());
        }
      }
    }
    String v = fieldInits.get(fieldKey);
    return v == null || "?".equals(v) || v.startsWith("empty:") || fieldWrites.contains(fieldKey) ? null : v;
  }

  private final Map<String, ClassFacts> classes;
  private final Map<String, List<String>> subtypes = new HashMap<>();
  private final Hierarchy hierarchy;
  private final LibraryModels models;
  private final Thresholds thresholds;
  private final Nullable nullable;
  private final Map<String, Optional<NullReason>> memo = new HashMap<>();
  private final Map<String, Integer> callSites = new HashMap<>();
  private final Map<String, Integer> nullChecks = new HashMap<>();
  /** Call sites in test code (parity only): counted on top of the main-code ones. */
  private final Map<String, Integer> testSites = new HashMap<>();
  private final Map<String, Integer> testChecks = new HashMap<>();
  private Map<String, Map<String, List<Finding>>> findings;

  /**
   * Which annotations mark a nullable member: simple names match any package, qualified names one type;
   * {@code ignored} lists qualified names to leave out (for example a schema hint such as Avro's).
   */
  public record Nullable(Set<String> annotations, Set<String> ignored) {
    public static final Nullable DEFAULT = new Nullable(Set.of("Nullable", "CheckForNull", "NullableDecl"), Set.of());

    public boolean matches(Map<String, String> present) {
      if (present.keySet().stream().anyMatch(ignored::contains)) {
        return false;
      }
      return present.keySet().stream().anyMatch(annotations::contains);
    }
  }

  /**
   * @param nullable           annotations that mark nullable members
   * @param dataSources        integers read from binary or text input count as untrusted sizes
   * @param logParametersSafe  parameterised log arguments ({@code log.info("x {}", v)}) are safe (the layout encodes CR/LF)
   * @param referenceParity    also report what the reference analyzer reports where this plugin deliberately differs
   */
  public record Options(Nullable nullable, boolean dataSources, boolean logParametersSafe, boolean referenceParity) {
    public static final Options DEFAULT = new Options(Nullable.DEFAULT, true, true, false);
  }

  private final Options options;

  public Summaries(Collection<ClassFacts> facts, Hierarchy hierarchy, LibraryModels models, Thresholds thresholds) {
    this(facts, hierarchy, models, thresholds, Options.DEFAULT);
  }

  public Summaries(Collection<ClassFacts> facts, Hierarchy hierarchy, LibraryModels models, Thresholds thresholds, Options options) {
    this(facts, List.of(), hierarchy, models, thresholds, options);
  }

  /** @param statsOnly classes (test code) whose calls count for call-site statistics only */
  public Summaries(Collection<ClassFacts> facts, Collection<ClassFacts> statsOnly, Hierarchy hierarchy, LibraryModels models,
    Thresholds thresholds, Options options) {
    countTests(statsOnly);
    this.options = options;
    this.nullable = options.nullable();
    this.classes = byName(facts);
    for (ClassFacts c : classes.values()) {
      if (c.superName() != null) {
        subtypes.computeIfAbsent(c.superName(), k -> new ArrayList<>()).add(c.name());
      }
      c.interfaces().forEach(i -> subtypes.computeIfAbsent(i, k -> new ArrayList<>()).add(c.name()));
      for (MethodFacts m : c.methods().values()) {
        m.uses().callSites().forEach((k, n) -> callSites.merge(k, n, Integer::sum));
        m.uses().nullChecks().forEach((k, n) -> nullChecks.merge(k, n, Integer::sum));
      }
    }
    this.hierarchy = hierarchy;
    this.models = models;
    this.thresholds = thresholds;
  }

  public static Map<String, ClassFacts> byName(Collection<ClassFacts> facts) {
    Map<String, ClassFacts> m = new HashMap<>();
    facts.forEach(c -> m.put(c.name(), c));
    return m;
  }

  public Map<String, ClassFacts> classes() {
    return classes;
  }

  private final Set<Object> statsSources = new java.util.HashSet<>();

  /**
   * Calls in classes that count for call-site statistics only (test code of a module analysed later).
   *
   * @param source identifies the classes (a directory), so each is added once
   */
  public synchronized boolean addStats(Object source, Collection<ClassFacts> statsOnly) {
    if (!statsSources.add(source)) {
      return false;
    }
    countTests(statsOnly);
    return true;
  }

  private void countTests(Collection<ClassFacts> statsOnly) {
    for (ClassFacts c : statsOnly) {
      for (MethodFacts m : c.methods().values()) {
        m.uses().callSites().forEach((k, n) -> testSites.merge(k, n, Integer::sum));
        m.uses().nullChecks().forEach((k, n) -> testChecks.merge(k, n, Integer::sum));
      }
    }
  }

  public synchronized boolean hasStats(Object source) {
    return statsSources.contains(source);
  }

  /** Library answers computed before more libraries were added are recomputed on demand. */
  public synchronized void forgetLibraryAnswers() {
    memo.clear();
  }

  public Hierarchy hierarchy() {
    return hierarchy;
  }

  public Thresholds thresholds() {
    return thresholds;
  }

  public Options options() {
    return options;
  }

  public List<String> subtypes(String type) {
    return subtypes.getOrDefault(type, List.of());
  }

  public boolean isSubtype(String type, String superType) {
    return hierarchy.selfAndSupertypes(type).contains(superType);
  }

  // ---------------------------------------------------------------- findings from bytecode

  /** Findings of {@code rule} in {@code sourcePath} (e.g. {@code com/acme/Foo.java}). */
  public synchronized List<Finding> findings(String rule, String sourcePath) {
    if (findings == null) {
      long start = System.currentTimeMillis();
      findings = new HashMap<>();
      List<Finding> all = new ProjectAnalyses(this).run();
      org.slf4j.LoggerFactory.getLogger(Summaries.class).info("Deep Java whole-program analyses (taint, locks, exceptions): {} findings in {} ms",
        all.size(), System.currentTimeMillis() - start);
      for (Finding f : all) {
        findings.computeIfAbsent(f.rule(), k -> new HashMap<>()).computeIfAbsent(f.sourcePath(), k -> new ArrayList<>()).add(f);
      }
    }
    return findings.getOrDefault(rule, Map.of()).getOrDefault(sourcePath, List.of());
  }

  // ---------------------------------------------------------------- call resolution

  public record Call(String owner, String name, String desc, boolean exact, boolean knownLookup) {
    public String nameDesc() {
      return name + desc;
    }
  }

  public static Call parse(String callKey) {
    boolean knownLookup = callKey.startsWith(BytecodeScanner.KNOWN_LOOKUP);
    String k = knownLookup ? callKey.substring(1) : callKey;
    boolean exact = k.startsWith("!");
    if (exact) {
      k = k.substring(1);
    }
    int hash = k.indexOf('#');
    int paren = k.indexOf('(', hash);
    return new Call(k.substring(0, hash), k.substring(hash + 1, paren), k.substring(paren), exact, knownLookup);
  }

  /**
   * Call key of the project method {@code name} with {@code argc} arguments declared by {@code owner} or a
   * superclass, when exactly one exists: for calls whose symbol the syntax tree cannot resolve, such as
   * Lombok-generated getters, which exist only in the bytecode.
   */
  public String findMethod(String owner, String name, int argc) {
    for (ClassFacts c = classes.get(owner); c != null; c = c.superName() == null ? null : classes.get(c.superName())) {
      List<String> found = new ArrayList<>();
      for (String nd : c.methods().keySet()) {
        if (nd.startsWith(name + "(") && org.objectweb.asm.Type.getArgumentTypes(nd.substring(name.length())).length == argc) {
          found.add(nd);
        }
      }
      if (found.size() == 1) {
        return BytecodeScanner.callKey(false, owner, name, found.get(0).substring(name.length()));
      }
      if (found.size() > 1) {
        return null;
      }
    }
    return null;
  }

  private final Map<String, Boolean> maybeDeref = new HashMap<>();

  /**
   * Parameter {@code index} of {@code m} is dereferenced on some path without a null check, in {@code m} or in a
   * method it passes the parameter to (up to four calls deep).
   */
  public synchronized boolean maybeDereferences(Method m, int index) {
    return maybeDereferences(m, index, 0);
  }

  private boolean maybeDereferences(Method m, int index, int depth) {
    if (m.facts().uses().maybeDerefParams().contains(index)) {
      return true;
    }
    if (depth >= 4) {
      return false;
    }
    String memoKey = m.owner().name() + "#" + m.nameDesc() + "#" + index;
    Boolean known = maybeDeref.get(memoKey);
    if (known != null) {
      return known;
    }
    maybeDeref.put(memoKey, false);
    boolean r = false;
    for (String fwd : m.facts().uses().paramForwards().getOrDefault(index, Set.of())) {
      int bar = fwd.lastIndexOf('|');
      List<Method> t = targets(fwd.substring(0, bar));
      int arg = Integer.parseInt(fwd.substring(bar + 1));
      if (!t.isEmpty() && t.size() <= thresholds.maxTargets() && t.stream().allMatch(x -> maybeDereferences(x, arg, depth + 1))) {
        r = true;
        break;
      }
    }
    maybeDeref.put(memoKey, r);
    return r;
  }

  /** Project implementations a call can reach (class hierarchy analysis over project types). */
  public List<Method> targets(String callKey) {
    Call c = parse(callKey);
    List<Method> out = new ArrayList<>();
    for (ClassFacts cf : targetClasses(c.owner(), c.nameDesc(), c.exact())) {
      out.add(new Method(cf, c.nameDesc(), cf.methods().get(c.nameDesc())));
    }
    return out;
  }

  private List<ClassFacts> targetClasses(String owner, String nameDesc, boolean exact) {
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

  /**
   * Every project implementation of the call always throws: it has no return, or each of its returns comes right
   * after a call that never returns ({@code addError(msg); fail(); return;}).
   */
  public boolean neverReturns(String callKey) {
    return neverReturns(callKey, 0);
  }

  private final Map<String, Boolean> never = new HashMap<>();

  private synchronized boolean neverReturns(String callKey, int depth) {
    Boolean known = never.get(callKey);
    if (known != null) {
      return known;
    }
    never.put(callKey, false);
    Call c = parse(callKey);
    List<ClassFacts> targets = targetClasses(c.owner(), c.nameDesc(), c.exact());
    boolean r = !targets.isEmpty() && targets.stream().allMatch(t -> {
      MethodFacts m = t.methods().get(c.nameDesc());
      if (!m.nulls().returnsNormally()) {
        return true;
      }
      Map<Integer, Set<String>> returns = m.ex().returnsAfter();
      return depth < 4 && !returns.isEmpty() && returns.values().stream()
        .allMatch(calls -> !calls.isEmpty() && calls.stream().allMatch(k -> neverReturns(k, depth + 1)));
    });
    never.put(callKey, r);
    return r;
  }

  private final Map<String, Boolean> indexes = new HashMap<>();

  /** Parameter {@code index} is used as an index without a check, in {@code m} or a callee it is passed to. */
  public synchronized boolean indexesParameter(Method m, int index) {
    return indexesParameter(m, index, 0);
  }

  private boolean indexesParameter(Method m, int index, int depth) {
    if (m.facts().uses().indexParams().contains(index)) {
      return true;
    }
    String memoKey = m.owner().name() + "#" + m.nameDesc() + "#" + index;
    Boolean known = indexes.get(memoKey);
    if (known != null || depth >= 4) {
      return known != null && known;
    }
    indexes.put(memoKey, false);
    boolean r = false;
    for (String fwd : m.facts().uses().paramForwards().getOrDefault(index, Set.of())) {
      int bar = fwd.lastIndexOf('|');
      List<Method> t = targets(fwd.substring(0, bar));
      int arg = Integer.parseInt(fwd.substring(bar + 1));
      if (!t.isEmpty() && t.size() <= thresholds.maxTargets() && t.stream().allMatch(x -> indexesParameter(x, arg, depth + 1))) {
        r = true;
        break;
      }
    }
    indexes.put(memoKey, r);
    return r;
  }

  /** The class declaring {@code nameDesc}, searching {@code owner} and its project superclasses. */
  private ClassFacts declaring(String owner, String nameDesc) {
    for (ClassFacts c = classes.get(owner); c != null; c = c.superName() == null ? null : classes.get(c.superName())) {
      if (c.methods().containsKey(nameDesc)) {
        return c;
      }
    }
    return null;
  }

  private void collectOverrides(String type, String nameDesc, List<ClassFacts> out) {
    for (String sub : subtypes.getOrDefault(type, List.of())) {
      if (out.size() > thresholds.maxTargets()) {
        return;
      }
      ClassFacts c = classes.get(sub);
      if (c.methods().containsKey(nameDesc) && !out.contains(c)) {
        out.add(c);
      }
      collectOverrides(sub, nameDesc, out);
    }
  }

  // ---------------------------------------------------------------- nullness

  /** Call sites in main and test code (test code is only read in parity mode). */
  public CallStats callStats(String callKey) {
    return callStats(callKey, true);
  }

  /**
   * @param withTests count test callers too; without them the population is the one the reference analyzer sees,
   *                  which tests that dereference results directly cannot dilute
   */
  public synchronized CallStats callStats(String callKey, boolean withTests) {
    String k = BytecodeScanner.statKey(callKey);
    int sites = 0;
    int checked = 0;
    for (String copy : copies(k)) {
      sites += callSites.getOrDefault(copy, 0) + (withTests ? testSites.getOrDefault(copy, 0) : 0);
      checked += nullChecks.getOrDefault(copy, 0) + (withTests ? testChecks.getOrDefault(copy, 0) : 0);
    }
    return new CallStats(sites, checked);
  }

  private Map<String, List<String>> copyGroups;

  /**
   * The same method of identical copies of a class in other packages (same simple name, same methods): their
   * callers form one population.
   */
  private synchronized List<String> copies(String statKey) {
    if (copyGroups == null) {
      copyGroups = new HashMap<>();
      Map<String, List<String>> byShape = new HashMap<>();
      for (ClassFacts c : classes.values()) {
        byShape.computeIfAbsent(simpleName(c.name()) + "|" + new java.util.TreeSet<>(c.methods().keySet()), x -> new ArrayList<>()).add(c.name());
      }
      byShape.values().stream().filter(g -> g.size() > 1).forEach(g -> g.forEach(n -> copyGroups.put(n, g)));
    }
    int hash = statKey.indexOf('#');
    List<String> group = hash < 0 ? null : copyGroups.get(statKey.substring(0, hash));
    if (group == null) {
      return List.of(statKey);
    }
    List<String> out = new ArrayList<>();
    group.forEach(owner -> out.add(owner + statKey.substring(hash)));
    return out;
  }

  /**
   * Whether a dereference of the call's result should be reported: always for library-rooted reasons, and for
   * project-rooted ones only when most call sites check the result.
   */
  public boolean isReportable(String callKey, NullReason reason) {
    // parity: a "return null" path is reason enough, as for the reference analyzer
    if (reason.isStrong() || options.referenceParity()) {
      return true;
    }
    CallStats s = callStats(callKey);
    return s.sites() >= thresholds.minSites() && s.checked() >= thresholds.minRatio() * s.sites();
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
    Call c = parse(callKey);
    LibraryModels.Match lib = models.nullableReturn(c.owner(), c.name(), c.desc(), hierarchy);
    if (lib != null && c.knownLookup() && !lib.guards().isEmpty()) {
      // constant table, or a get(key) wrapper whose caller chooses the key: content is known to the author
      return null;
    }
    NullReason model = modelReason(c, lib);
    if (model == null || !options.referenceParity()) {
      return model != null ? model : projectReason(c);
    }
    // parity: a project implementation of a modelled method (a Future whose get(timeout) returns null) speaks for itself
    NullReason project = targetClasses(c.owner(), c.nameDesc(), c.exact()).isEmpty() ? null : projectReason(c);
    return project != null ? project : model;
  }

  private NullReason modelReason(Call c, LibraryModels.Match lib) {
    if (lib != null) {
      // parity: a model entry the library itself annotates nullable is a declared nullable, as for the reference analyzer
      boolean annotated = options.referenceParity() && (models.annotatedNullable(lib.entry()) || libraryAnnotated(c) != null);
      return new NullReason(display(c.owner(), c.name()), null, null, annotated ? NullReason.Kind.ANNOTATED : NullReason.Kind.LIBRARY, lib.guards());
    }
    // parity: null for a null argument counts as nullable, as for the reference analyzer
    if (options.referenceParity() && models.nullForNullArgument(c.owner(), c.name())) {
      return new NullReason(display(c.owner(), c.name()), null, null, NullReason.Kind.LIBRARY, Set.of());
    }
    return null;
  }

  private NullReason projectReason(Call c) {
    List<ClassFacts> targets = targetClasses(c.owner(), c.nameDesc(), c.exact());
    if (targets.size() > thresholds.maxTargets()) {
      return null;
    }
    if (targets.isEmpty()) {
      return libraryAnnotated(c);
    }
    for (ClassFacts cf : targets) {
      MethodFacts mf = cf.methods().get(c.nameDesc());
      if (!c.exact() && mf.nulls().onlyNull() && targets.size() > 1) {
        // a "return null" default that subclasses override: callers rely on the override
        continue;
      }
      String display = display(cf.name(), c.name());
      if (mf.nulls().localNullReturn() > 0) {
        return new NullReason(display, location(cf, mf.nulls().localNullReturn()), null, NullReason.Kind.LOCAL_NULL, Set.of());
      }
      // a declared nullable return is stronger evidence than a "return null" that needs call-site statistics
      if (mf.nulls().annotatedNullable() || nullable.matches(mf.info().annotations()) || nullableByDefault(cf, mf.info().annotations())) {
        return new NullReason(display, null, null, NullReason.Kind.ANNOTATED, Set.of());
      }
      Integer line = reachableNullReturn(mf);
      if (line != null) {
        NullReason.Kind kind = returnsCollection(c.desc()) ? NullReason.Kind.COLLECTION_NULL : NullReason.Kind.RETURNS_NULL;
        return new NullReason(display, location(cf, line), null, kind, Set.of());
      }
      if (isMapperImplementation(cf, c.desc(), mf.info().access())) {
        return new NullReason(display, null, null, NullReason.Kind.NULL_FOR_NULL_ARGUMENT, Set.of());
      }
      for (String field : mf.nulls().returnedFields()) {
        ClassFacts owner = classes.get(field.substring(0, field.indexOf('#')));
        ClassFacts.FieldFacts ff = owner == null ? null : owner.fields().get(field.substring(field.indexOf('#') + 1));
        if (ff != null && nullable.matches(ff.annotations())) {
          return new NullReason(display, simpleName(owner.name()) + "." + field.substring(field.indexOf('#') + 1), null,
            NullReason.Kind.NULLABLE_FIELD, Set.of());
        }
      }
      List<Map.Entry<String, Integer>> calls = new ArrayList<>(mf.nulls().returnedCalls().entrySet());
      calls.sort(Map.Entry.comparingByValue());
      for (Map.Entry<String, Integer> call : calls) {
        // parity: a lookup wrapper (Registry.getInstance().get(key)) passes the map's null on, whatever the key
        String causeKey = options.referenceParity() && call.getKey().startsWith(BytecodeScanner.KNOWN_LOOKUP) ? call.getKey().substring(1) : call.getKey();
        Optional<NullReason> cause = nullReturn(causeKey);
        // a mapper's null comes back only for a null argument: through a delegate the argument is unknown
        boolean argumentNull = cause.isPresent() && cause.get().root().kind() == NullReason.Kind.NULL_FOR_NULL_ARGUMENT && !options.referenceParity();
        if (cause.isPresent() && !argumentNull && Collections.disjoint(cause.get().guards(), mf.nulls().invokedNames())) {
          return new NullReason(display, location(cf, call.getValue()), cause.get(), NullReason.Kind.RETURNS_CALL, Set.of());
        }
      }
    }
    return null;
  }

  private static final java.util.regex.Pattern COLLECTION =
    java.util.regex.Pattern.compile("\\)(\\[.*|Ljava/util/\\w*(List|Set|Map|Collection);|Ljava/lang/Iterable;)");

  /** Return type is an array, a JDK collection or a map: callers expect an empty one rather than null. */
  static boolean returnsCollection(String desc) {
    return COLLECTION.matcher(desc).find();
  }

  private static final Set<String> NON_NULL = Set.of("NonNull", "Nonnull", "NotNull");

  /** The class or package-info declares returns nullable by default and the method does not opt out. */
  private boolean nullableByDefault(ClassFacts cf, Map<String, String> methodAnnotations) {
    if (methodAnnotations.keySet().stream().anyMatch(NON_NULL::contains)) {
      return false;
    }
    int slash = cf.name().lastIndexOf('/');
    ClassFacts pkg = classes.get(cf.name().substring(0, slash + 1) + "package-info");
    return cf.annotations().containsKey(NULLABLE_BY_DEFAULT) || pkg != null && pkg.annotations().containsKey(NULLABLE_BY_DEFAULT)
      || hierarchy.library(cf.name().substring(0, slash + 1) + "package-info").annotations().containsKey(NULLABLE_BY_DEFAULT);
  }

  private static final String NULLABLE_BY_DEFAULT = "ReturnValuesAreNullableByDefault";

  /**
   * A library method annotated nullable in its bytecode (on the method, its return type, the field a plain getter
   * returns, or as a class / package default), searching the owner and its supertypes.
   */
  private NullReason libraryAnnotated(Call c) {
    for (String type : hierarchy.selfAndSupertypes(c.owner())) {
      Hierarchy.Library lib = hierarchy.library(type);
      Map<String, String> anns = lib.methods().get(c.nameDesc());
      if (anns == null) {
        continue;
      }
      String display = display(type, c.name());
      if (nullable.matches(anns)) {
        return new NullReason(display, null, null, NullReason.Kind.ANNOTATED, Set.of());
      }
      String field = lib.getterFields().get(c.nameDesc());
      if (field != null) {
        String fieldOwner = field.substring(0, field.indexOf('#'));
        Map<String, String> fa = hierarchy.library(fieldOwner).fields().get(field.substring(field.indexOf('#') + 1));
        if (fa != null && nullable.matches(fa)) {
          return new NullReason(display, simpleName(fieldOwner) + "." + field.substring(field.indexOf('#') + 1), null,
            NullReason.Kind.NULLABLE_FIELD, Set.of());
        }
      }
      if (!anns.keySet().stream().anyMatch(NON_NULL::contains) && org.objectweb.asm.Type.getReturnType(c.desc()).getSort() >= org.objectweb.asm.Type.ARRAY) {
        int slash = type.lastIndexOf('/');
        if (lib.annotations().containsKey(NULLABLE_BY_DEFAULT)
          || hierarchy.library(type.substring(0, slash + 1) + "package-info").annotations().containsKey(NULLABLE_BY_DEFAULT)) {
          return new NullReason(display, null, null, NullReason.Kind.ANNOTATED, Set.of());
        }
      }
    }
    return null;
  }

  /**
   * Parameter {@code index} of a library method that a project method overrides is annotated nullable: the
   * override must handle null. Returns the display name of the library method, or null.
   */
  public String libraryNullableParameter(String owner, String nameDesc, int index) {
    for (String type : hierarchy.selfAndSupertypes(owner)) {
      List<Map<String, String>> params = hierarchy.library(type).params().get(nameDesc);
      if (params != null && index < params.size() && nullable.matches(params.get(index))) {
        return display(type, nameDesc.substring(0, nameDesc.indexOf('(')));
      }
    }
    return null;
  }

  /**
   * A mapping method of a MapStruct implementation: generated mappers return null when the source is null
   * ({@code if (dto == null) return null;}), unless configured otherwise.
   */
  private boolean isMapperImplementation(ClassFacts cf, String desc, int access) {
    if ((access & (org.objectweb.asm.Opcodes.ACC_STATIC | org.objectweb.asm.Opcodes.ACC_SYNTHETIC | org.objectweb.asm.Opcodes.ACC_BRIDGE)) != 0
      || org.objectweb.asm.Type.getReturnType(desc).getSort() < org.objectweb.asm.Type.ARRAY
      || java.util.Arrays.stream(org.objectweb.asm.Type.getArgumentTypes(desc)).noneMatch(t -> t.getSort() >= org.objectweb.asm.Type.ARRAY)) {
      return false;
    }
    List<String> supers = new ArrayList<>(cf.interfaces());
    if (cf.superName() != null) {
      supers.add(cf.superName());
    }
    for (String t : supers) {
      ClassFacts p = classes.get(t);
      Map<String, String> anns = p != null ? p.annotations() : hierarchy.library(t).annotations();
      if (anns.containsKey("org.mapstruct.Mapper") || anns.containsKey("org.mapstruct.DecoratedWith")) {
        return true;
      }
    }
    return false;
  }

  /** First line of a {@code return null} that is not only reached after calls that always throw. */
  private Integer reachableNullReturn(MethodFacts mf) {
    Integer best = null;
    for (Map.Entry<Integer, Set<String>> r : mf.nulls().nullReturns().entrySet()) {
      boolean reachable = r.getValue().isEmpty() || !r.getValue().stream().allMatch(this::neverReturns);
      if (reachable && (best == null || r.getKey() < best)) {
        best = r.getKey();
      }
    }
    return best;
  }

  public static String location(ClassFacts c, int line) {
    String file = c.sourceFile() != null ? c.sourceFile() : simpleName(c.name());
    return line > 0 ? file + ":" + line : file;
  }

  public static String display(String owner, String name) {
    return simpleName(owner) + "." + name + "()";
  }

  public static String simpleName(String internalName) {
    return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '.');
  }
}
