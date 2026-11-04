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
      findings = new HashMap<>();
      for (Finding f : new ProjectAnalyses(this).run()) {
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

  /** Every project implementation of the call always throws ({@code reportError(...); return null;}). */
  public boolean neverReturns(String callKey) {
    Call c = parse(callKey);
    List<ClassFacts> targets = targetClasses(c.owner(), c.nameDesc(), c.exact());
    return !targets.isEmpty() && targets.stream().noneMatch(t -> t.methods().get(c.nameDesc()).nulls().returnsNormally());
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

  public CallStats callStats(String callKey) {
    String k = BytecodeScanner.statKey(callKey);
    return new CallStats(callSites.getOrDefault(k, 0), nullChecks.getOrDefault(k, 0));
  }

  /**
   * Whether a dereference of the call's result should be reported: always for library-rooted reasons, and for
   * project-rooted ones only when most call sites check the result.
   */
  public boolean isReportable(String callKey, NullReason reason) {
    if (reason.isStrong()) {
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
    if (lib != null) {
      if (c.knownLookup() && !lib.guards().isEmpty()) {
        // constant table, or a get(key) wrapper whose caller chooses the key: content is known to the author
        return null;
      }
      return new NullReason(display(c.owner(), c.name()), null, null, NullReason.Kind.LIBRARY, lib.guards());
    }
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
      Integer line = reachableNullReturn(mf);
      if (line != null) {
        NullReason.Kind kind = returnsCollection(c.desc()) ? NullReason.Kind.COLLECTION_NULL : NullReason.Kind.RETURNS_NULL;
        return new NullReason(display, location(cf, line), null, kind, Set.of());
      }
      if (mf.nulls().annotatedNullable() || nullable.matches(mf.info().annotations()) || nullableByDefault(cf, mf.info().annotations())) {
        return new NullReason(display, null, null, NullReason.Kind.ANNOTATED, Set.of());
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
        Optional<NullReason> cause = nullReturn(call.getKey());
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
