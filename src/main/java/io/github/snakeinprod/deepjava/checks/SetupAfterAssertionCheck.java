package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.ExpressionStatementTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;

/** In tests: state changed after the last assertion is never checked. */
@Rule(key = SetupAfterAssertionCheck.KEY)
public class SetupAfterAssertionCheck extends DeepCheck {

  public static final String KEY = "SetupAfterLastAssertion";

  public SetupAfterAssertionCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.METHOD);
  }

  @Override
  public void visitNode(Tree tree) {
    MethodTree m = (MethodTree) tree;
    if (m.block() == null || m.modifiers().annotations().stream().noneMatch(a -> a.symbolType().name().matches("Test|ParameterizedTest|RepeatedTest"))) {
      return;
    }
    List<StatementTree> body = m.block().body();
    int last = -1;
    for (int i = 0; i < body.size(); i++) {
      if (Trees.collect(body.get(i), MethodInvocationTree.class).stream().anyMatch(AssertionAfterThrowCheck::isAssertion)) {
        last = i;
      }
    }
    if (last < 0) {
      return;
    }
    for (int i = last + 1; i < body.size(); i++) {
      StatementTree s = body.get(i);
      if (s instanceof ExpressionStatementTree es && Trees.skip(es.expression()) instanceof MethodInvocationTree call
        && Trees.name(call).matches("(?i)(close|shutdown|stop|clean.*|delete.*|reset.*|clear|dispose|release|teardown|disconnect|destroy|remove.*|verify.*|unlock|interrupt|join|cancel)")) {
        continue;
      }
      if (s.is(Tree.Kind.EXPRESSION_STATEMENT, Tree.Kind.VARIABLE)) {
        reportIssue(s, "This runs after the last assertion (line " + Trees.line(body.get(last)) + "); its effect is never checked.");
        return;
      }
    }
  }
}
