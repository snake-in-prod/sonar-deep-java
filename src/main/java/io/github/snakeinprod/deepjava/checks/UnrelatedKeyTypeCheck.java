package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.semantic.Type;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;

/**
 * {@code Map<Long, V>.get(intValue)}: a key of an unrelated type never matches, the lookup always misses. Also
 * through a generic wrapper ({@code Dao<K, V>.find(Object key)} that looks the key up in its {@code Map<K, V>} field).
 */
@Rule(key = UnrelatedKeyTypeCheck.KEY)
public class UnrelatedKeyTypeCheck extends DeepCheck {

  public static final String KEY = "UnrelatedKeyType";

  private static final Set<String> MAP_LOOKUPS = Set.of("get", "containsKey", "remove", "getOrDefault");
  private static final Set<String> COLLECTION_LOOKUPS = Set.of("contains", "remove", "indexOf", "lastIndexOf");

  public UnrelatedKeyTypeCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.METHOD_INVOCATION);
  }

  @Override
  public void visitNode(Tree tree) {
    MethodInvocationTree mit = (MethodInvocationTree) tree;
    ExpressionTree receiver = Trees.receiver(mit);
    if (mit.arguments().isEmpty() || receiver == null || mit.methodSymbol().isUnknown()) {
      return;
    }
    String name = Trees.name(mit);
    Type r = receiver.symbolType();
    ExpressionTree arg = mit.arguments().get(0);
    if (MAP_LOOKUPS.contains(name) && r.isSubtypeOf("java.util.Map") && objectParam(mit)) {
      check(arg, typeArgument(r, "java.util.Map", 0), r.name());
    } else if (COLLECTION_LOOKUPS.contains(name) && r.isSubtypeOf("java.util.Collection") && objectParam(mit)) {
      check(arg, typeArgument(r, "java.util.Collection", 0), r.name());
    } else {
      wrapper(mit, receiver, arg);
    }
  }

  /** A project method that passes its parameter to a lookup on a field map. */
  private void wrapper(MethodInvocationTree mit, ExpressionTree receiver, ExpressionTree arg) {
    String key = Trees.callKey(mit.methodSymbol());
    List<Summaries.Method> targets = key == null ? List.of() : summaries.get().targets(key);
    for (int i = 0; i < mit.arguments().size() && !targets.isEmpty(); i++) {
      int index = i;
      String field = targets.get(0).facts().uses().keyParams().get(i);
      if (field == null || !targets.stream().allMatch(t -> field.equals(t.facts().uses().keyParams().get(index)))) {
        continue;
      }
      Type mapType = fieldType(receiver.symbolType(), field.substring(field.indexOf('#') + 1));
      if (mapType == null || !mapType.isSubtypeOf("java.util.Map")) {
        continue;
      }
      Type k = substitute(typeArgument(mapType, "java.util.Map", 0), receiver.symbolType());
      check(mit.arguments().get(i), k, receiver.symbolType().name() + " (through its map \"" + field.substring(field.indexOf('#') + 1) + "\")");
    }
  }

  private void check(ExpressionTree arg, Type keyType, String where) {
    Type a = arg.symbolType();
    if (keyType != null && keyType.isTypeVar() && summaries.get().options().referenceParity() && !a.isUnknown() && !a.isTypeVar()
      && !a.is("java.lang.Object") && !arg.is(Tree.Kind.NULL_LITERAL)) {
      // parity: a generic DAO looking up a String in its Map<K, V>, which only matches when K is String
      reportIssue(arg, "A \"" + a.name() + "\" is looked up in a " + where + " keyed by the type variable \"" + keyType.name()
        + "\": it only matches when \"" + keyType.name() + "\" is \"" + a.name() + "\".");
      return;
    }
    if (keyType == null || keyType.isUnknown() || a.isUnknown() || a.is("java.lang.Object") || keyType.is("java.lang.Object")
      || arg.is(Tree.Kind.NULL_LITERAL) || keyType.name().isEmpty() || keyType.name().startsWith("?") || keyType.isTypeVar() || a.isTypeVar() || a.isNullType() || keyType.isIntersectionType()) {
      return;
    }
    String argName = a.isPrimitive() ? boxed(a) : a.fullyQualifiedName();
    if (argName == null || keyType.is(argName) || related(a, keyType)) {
      return;
    }
    reportIssue(arg, "A \"" + simple(argName) + "\" is looked up in a " + where + " keyed by \"" + keyType.name()
      + "\": it never matches.");
  }

  private static boolean related(Type a, Type k) {
    if (a.isPrimitive()) {
      return k.is("java.lang.Number") || k.is("java.io.Serializable") || k.is("java.lang.Comparable");
    }
    if (a.isSubtypeOf(k) || k.isSubtypeOf(a)) {
      return true;
    }
    // an interface and a non-final class may meet in a subclass
    Symbol.TypeSymbol as = a.symbol();
    Symbol.TypeSymbol ks = k.symbol();
    return as.isInterface() && !ks.isFinal() || ks.isInterface() && !as.isFinal() || a.isArray() || k.isArray();
  }

  private static String boxed(Type p) {
    return switch (p.fullyQualifiedName()) {
      case "int" -> "java.lang.Integer";
      case "long" -> "java.lang.Long";
      case "short" -> "java.lang.Short";
      case "byte" -> "java.lang.Byte";
      case "char" -> "java.lang.Character";
      case "boolean" -> "java.lang.Boolean";
      case "double" -> "java.lang.Double";
      case "float" -> "java.lang.Float";
      default -> null;
    };
  }

  private static String simple(String fqn) {
    return fqn.substring(fqn.lastIndexOf('.') + 1);
  }

  /** The lookup takes {@code Object} (not a typed overload such as a project's {@code get(K)}). */
  private static boolean objectParam(MethodInvocationTree mit) {
    List<Type> params = mit.methodSymbol().parameterTypes();
    return !params.isEmpty() && params.get(0).is("java.lang.Object");
  }

  /** Type argument {@code index} of {@code type} seen as {@code generic} ({@code Map}), or null. */
  private static Type typeArgument(Type type, String generic, int index) {
    if (type.isParameterized() && type.typeArguments().size() > index && ("java.util.Map".equals(generic) || type.typeArguments().size() == 1)
      && !type.typeArguments().get(index).isTypeVar()) {
      // IMap<K, V>, ConcurrentMap<K, V>, List<E>: the key (element) is the first type argument
      return type.typeArguments().get(index);
    }
    if (type.is(generic) || type.erasure().is(generic)) {
      return type.typeArguments().size() > index ? type.typeArguments().get(index) : null;
    }
    Symbol.TypeSymbol s = type.symbol();
    if (s == null || s.isUnknown()) {
      return null;
    }
    List<Type> supers = new java.util.ArrayList<>(s.interfaces());
    if (s.superClass() != null) {
      supers.add(s.superClass());
    }
    for (Type sup : supers) {
      Type t = typeArgument(sup, generic, index);
      if (t != null) {
        return substitute(t, type);
      }
    }
    return null;
  }

  /** {@code K} of {@code Dao<K, V>} replaced by the argument of the parameterised {@code owner}. */
  private static Type substitute(Type t, Type owner) {
    if (t == null || !owner.isParameterized()) {
      return t;
    }
    List<Type> vars = owner.symbol().type().typeArguments();
    for (int i = 0; i < vars.size() && i < owner.typeArguments().size(); i++) {
      if (t.isTypeVar() && vars.get(i).name().equals(t.name())) {
        return owner.typeArguments().get(i);
      }
    }
    return t;
  }

  private static Type fieldType(Type owner, String field) {
    for (Type t = owner; t != null && !t.isUnknown(); t = t.symbol().superClass()) {
      for (Symbol m : t.symbol().lookupSymbols(field)) {
        if (m.isVariableSymbol()) {
          return m.type();
        }
      }
    }
    return null;
  }
}
