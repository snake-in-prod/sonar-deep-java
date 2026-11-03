package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.ExpressionStatementTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;

/** In tests: an assertion after a call that always throws never runs. */
@Rule(key = AssertionAfterThrowCheck.KEY)
public class AssertionAfterThrowCheck extends DeepCheck {

  public static final String KEY = "AssertionAfterAlwaysThrows";

  public AssertionAfterThrowCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.BLOCK);
  }

  @Override
  public void visitNode(Tree tree) {
    List<StatementTree> body = ((BlockTree) tree).body();
    for (int k = 0; k < body.size(); k++) {
      String thrower = thrower(body.get(k));
      if (thrower == null) {
        continue;
      }
      for (int j = k + 1; j < body.size(); j++) {
        for (MethodInvocationTree m : Trees.collect(body.get(j), MethodInvocationTree.class)) {
          if (isAssertion(m)) {
            reportIssue(m, "\"" + thrower + "\" at line " + Trees.line(body.get(k)) + " always throws, so this assertion never runs.");
            return;
          }
        }
      }
    }
  }

  static boolean isAssertion(MethodInvocationTree m) {
    String n = Trees.name(m);
    return n.startsWith("assert") || n.equals("verify") || n.startsWith("then") && !m.methodSymbol().isUnknown()
      && m.methodSymbol().owner().type().fullyQualifiedName().contains("Assertions");
  }

  private String thrower(StatementTree s) {
    if (!(s instanceof ExpressionStatementTree es) || !(Trees.skip(es.expression()) instanceof MethodInvocationTree m) || m.methodSymbol().isUnknown()) {
      return null;
    }
    String n = Trees.name(m);
    String owner = m.methodSymbol().owner().type().fullyQualifiedName();
    if ("fail".equals(n) && (owner.startsWith("org.junit") || owner.startsWith("org.assertj") || owner.equals("junit.framework.Assert"))) {
      return n + "()";
    }
    String key = Trees.callKey(m.methodSymbol());
    return key != null && summaries.get().neverReturns(key) ? n + "()" : null;
  }
}
