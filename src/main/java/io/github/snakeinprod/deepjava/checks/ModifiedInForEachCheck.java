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
    return List.of(Tree.Kind.FOR_EACH_STATEMENT);
  }

  @Override
  public void visitNode(Tree tree) {
    ForEachStatement loop = (ForEachStatement) tree;
    ExpressionTree iterated = Trees.skip(loop.expression());
    boolean mapView = false;
    if (iterated instanceof MethodInvocationTree view && List.of("keySet", "values", "entrySet").contains(Trees.name(view))
      && Trees.receiver(view) != null) {
      iterated = Trees.receiver(view);
      mapView = true;
    }
    if (Trees.symbol(iterated) == null || iterated.symbolType().fullyQualifiedName().matches(".*(Concurrent|CopyOnWrite).*")) {
      return;
    }
    for (MethodInvocationTree m : Trees.collect(loop.statement(), MethodInvocationTree.class)) {
      String n = Trees.name(m);
      boolean mutates = mapView ? Set.of("remove", "clear", "putAll").contains(n) : MUTATORS.contains(n);
      if (mutates && Trees.receiver(m) != null && Trees.same(Trees.receiver(m), iterated) && !leavesLoop(m)) {
        reportIssue(m, "\"" + n + "()\" changes the collection that this loop iterates: the next iteration throws "
          + "ConcurrentModificationException. Use an Iterator or removeIf().");
      }
    }
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
    return !after.isEmpty() && after.get(0).is(Tree.Kind.BREAK_STATEMENT, Tree.Kind.RETURN_STATEMENT, Tree.Kind.THROW_STATEMENT);
  }
}
