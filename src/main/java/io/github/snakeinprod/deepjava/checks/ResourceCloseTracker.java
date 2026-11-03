package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.ExpressionStatementTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;

/**
 * Finds statements of a block that close a local resource ({@code r.close()} or a helper whose summary closes that
 * parameter), then hands each later use of the resource to the rule.
 */
abstract class ResourceCloseTracker extends DeepCheck {

  ResourceCloseTracker(Supplier<Summaries> summaries) {
    super(summaries);
  }

  /** How a statement closes a variable. */
  record Close(Symbol var, String how, int line) {
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.BLOCK);
  }

  @Override
  public void visitNode(Tree tree) {
    List<StatementTree> body = ((BlockTree) tree).body();
    for (int k = 0; k < body.size(); k++) {
      Close c = closeIn(body.get(k));
      if (c == null || !c.var().type().isSubtypeOf("java.lang.AutoCloseable")) {
        continue;
      }
      for (int j = k + 1; j < body.size(); j++) {
        StatementTree later = body.get(j);
        Close again = closeIn(later);
        if (again != null && again.var().equals(c.var())) {
          onClosedAgain(c, again, later);
          break;
        }
        boolean used = false;
        for (IdentifierTree id : Trees.collect(later, IdentifierTree.class)) {
          if (c.var().equals(id.symbol()) && !isAssigned(id)) {
            onUseAfterClose(c, id);
            used = true;
            break;
          }
        }
        if (used || Trees.assigns(later, c.var())) {
          break;
        }
      }
    }
  }

  abstract void onClosedAgain(Close first, Close second, StatementTree where);

  abstract void onUseAfterClose(Close close, IdentifierTree use);

  private static boolean isAssigned(IdentifierTree id) {
    return id.parent() instanceof org.sonar.plugins.java.api.tree.AssignmentExpressionTree a && a.variable() == id;
  }

  /** {@code r.close();} or {@code helper(r);} where helper closes that parameter. */
  Close closeIn(StatementTree s) {
    if (!(s instanceof ExpressionStatementTree es) || !(Trees.skip(es.expression()) instanceof MethodInvocationTree mit)) {
      return null;
    }
    if (mit.methodSelect() instanceof MemberSelectExpressionTree ms && "close".equals(ms.identifier().name()) && mit.arguments().isEmpty()) {
      Symbol v = Trees.symbol(ms.expression());
      return Trees.isLocal(v) ? new Close(v, "close()", Trees.line(s)) : null;
    }
    if (mit.methodSymbol().isUnknown()) {
      return null;
    }
    String key = Trees.callKey(mit.methodSymbol());
    List<Summaries.Method> targets = key == null ? List.of() : summaries.get().targets(key);
    if (targets.isEmpty()) {
      return null;
    }
    for (int i = 0; i < mit.arguments().size(); i++) {
      int index = i;
      ExpressionTree arg = mit.arguments().get(i);
      Symbol v = Trees.symbol(arg);
      if (Trees.isLocal(v) && targets.stream().allMatch(t -> t.facts().uses().closedParams().contains(index))) {
        return new Close(v, "\"" + targets.get(0).display() + "\"", Trees.line(s));
      }
    }
    return null;
  }
}
