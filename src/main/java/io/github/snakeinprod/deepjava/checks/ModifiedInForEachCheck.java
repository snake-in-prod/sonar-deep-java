package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.ForEachStatement;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;

/** A collection modified inside a for-each loop over it: {@code ConcurrentModificationException}. */
@Rule(key = ModifiedInForEachCheck.KEY)
public class ModifiedInForEachCheck extends DeepCheck {

  public static final String KEY = "CollectionModifiedInForEach";

  private static final Set<String> MUTATORS = Set.of("add", "addAll", "remove", "removeAll", "removeIf", "retainAll", "clear", "addFirst",
    "addLast", "removeFirst", "removeLast");

  public ModifiedInForEachCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.FOR_EACH_STATEMENT, Tree.Kind.METHOD_INVOCATION);
  }

  @Override
  public void visitNode(Tree tree) {
    if (tree instanceof MethodInvocationTree forEach) {
      lambda(forEach);
      return;
    }
    ForEachStatement loop = (ForEachStatement) tree;
    ExpressionTree iterated = Trees.skip(loop.expression());
    boolean mapView = false;
    if (iterated instanceof MethodInvocationTree view && List.of("keySet", "values", "entrySet").contains(Trees.name(view))
      && Trees.receiver(view) != null) {
      iterated = Trees.receiver(view);
      mapView = true;
    }
    if (!iterable(iterated, mapView)) {
      return;
    }
    for (MethodInvocationTree m : Trees.collect(loop.statement(), MethodInvocationTree.class)) {
      String n = Trees.name(m);
      boolean mutates = mapView ? MAP_MUTATORS.contains(n) : MUTATORS.contains(n);
      if (mutates && Trees.receiver(m) != null && Trees.same(Trees.receiver(m), iterated) && !leavesLoop(m)
        && !(mapView && "put".equals(n) && putsSameKey(m, loop))) {
        reportIssue(m, "\"" + n + "()\" changes the collection that this loop iterates: the next iteration throws "
          + "ConcurrentModificationException. Use an Iterator or removeIf().");
      } else if (mapView && Trees.receiver(m) == null && !leavesLoop(m)) {
        String helper = modifiesInHelper(m, Trees.symbol(iterated));
        if (helper != null) {
          reportIssue(m, "\"" + Trees.name(m) + "()\" calls \"" + helper + "()\" on the map that this loop iterates: the next "
            + "iteration throws ConcurrentModificationException.");
        }
      }
    }
  }

  private static final Set<String> MAP_MUTATORS = Set.of("remove", "clear", "put", "putAll");

  /**
   * JDK collections (a project collection may iterate over a copy); for map views any {@code Map}, distributed caches
   * included. Concurrent collections iterate weakly consistently, except in parity mode.
   */
  private boolean iterable(ExpressionTree iterated, boolean mapView) {
    String type = iterated.symbolType().fullyQualifiedName();
    if (Trees.symbol(iterated) == null) {
      return false;
    }
    boolean jdk = type.startsWith("java.util.") || mapView && iterated.symbolType().isSubtypeOf("java.util.Map");
    return jdk && (summaries.get().options().referenceParity() || !type.matches(".*(Concurrent|CopyOnWrite).*"));
  }

  /** {@code helper()} of this class puts into or removes from the field map being iterated, or null. */
  private String modifiesInHelper(MethodInvocationTree call, org.sonar.plugins.java.api.semantic.Symbol map) {
    if (!Trees.isField(map) || call.methodSymbol().isUnknown() || !call.methodSymbol().owner().equals(map.owner())) {
      return null;
    }
    String key = Trees.callKey(call.methodSymbol());
    List<Summaries.Method> targets = key == null ? List.of() : summaries.get().targets(key);
    String field = map.owner().type().erasure().fullyQualifiedName().replace('.', '/') + "#" + map.name();
    for (Summaries.Method t : targets) {
      for (String c : t.facts().uses().fieldCalls().getOrDefault(field, Set.of())) {
        String name = c.substring(0, c.indexOf('@'));
        if (MAP_MUTATORS.contains(name)) {
          return name;
        }
      }
    }
    return null;
  }

  /** {@code map.put(key, ...)} for the key being visited only replaces a value: not a structural change. */
  private static boolean putsSameKey(MethodInvocationTree put, ForEachStatement loop) {
    if (put.arguments().isEmpty()) {
      return false;
    }
    ExpressionTree key = Trees.skip(put.arguments().get(0));
    org.sonar.plugins.java.api.semantic.Symbol var = loop.variable().symbol();
    org.sonar.plugins.java.api.semantic.Symbol k = Trees.symbol(key);
    if (Trees.isLocal(k) && k.declaration() instanceof org.sonar.plugins.java.api.tree.VariableTree v && v.initializer() != null) {
      // String topic = entry.getKey(); map.put(topic, ...)
      key = Trees.skip(v.initializer());
    }
    if (var.equals(Trees.symbol(key))) {
      return true;
    }
    return key instanceof MethodInvocationTree get && "getKey".equals(Trees.name(get)) && Trees.receiver(get) != null
      && var.equals(Trees.symbol(Trees.receiver(get)));
  }

  /** {@code c.forEach(x -> c.remove(x))}: the same exception from inside the iteration. */
  private void lambda(MethodInvocationTree forEach) {
    ExpressionTree iterated = Trees.receiver(forEach);
    if (!"forEach".equals(Trees.name(forEach)) || iterated == null || forEach.arguments().size() != 1
      || !(forEach.arguments().get(0) instanceof org.sonar.plugins.java.api.tree.LambdaExpressionTree lambda)) {
      return;
    }
    boolean mapView = false;
    if (Trees.skip(iterated) instanceof MethodInvocationTree view && List.of("keySet", "values", "entrySet").contains(Trees.name(view))
      && Trees.receiver(view) != null) {
      iterated = Trees.receiver(view);
      mapView = true;
    }
    if (!iterable(iterated, mapView || iterated.symbolType().isSubtypeOf("java.util.Map"))) {
      return;
    }
    final ExpressionTree it = iterated;
    final boolean view = mapView || iterated.symbolType().isSubtypeOf("java.util.Map");
    lambda.body().accept(new org.sonar.plugins.java.api.tree.BaseTreeVisitor() {
      @Override
      public void visitMethodInvocation(MethodInvocationTree m) {
        String n = Trees.name(m);
        boolean mutates = view ? MAP_MUTATORS.contains(n) : MUTATORS.contains(n);
        if (mutates && Trees.receiver(m) != null && Trees.same(Trees.receiver(m), it)) {
          reportIssue(m, "\"" + n + "()\" changes the collection that forEach() iterates: it throws ConcurrentModificationException.");
        }
        super.visitMethodInvocation(m);
      }
    });
  }

  /** The modification is directly followed by break, return or throw. */
  private static boolean leavesLoop(Tree call) {
    Tree stmt = call;
    while (stmt != null && !(stmt instanceof StatementTree)) {
      stmt = stmt.parent();
    }
    if (stmt == null || !(stmt.parent() instanceof BlockTree block)) {
      return false;
    }
    List<StatementTree> after = Trees.statementsAfter(block, (StatementTree) stmt);
    return after.stream().anyMatch(s -> s.is(Tree.Kind.BREAK_STATEMENT, Tree.Kind.RETURN_STATEMENT, Tree.Kind.THROW_STATEMENT));
  }
}
