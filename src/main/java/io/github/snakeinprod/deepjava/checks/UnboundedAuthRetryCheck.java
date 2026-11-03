package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.DoWhileStatementTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.ForStatementTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.WhileStatementTree;

/** A login attempted in a loop with no attempt counter or limit (CWE-307). */
@Rule(key = UnboundedAuthRetryCheck.KEY)
public class UnboundedAuthRetryCheck extends DeepCheck {

  public static final String KEY = "UnboundedAuthRetry";

  public UnboundedAuthRetryCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.WHILE_STATEMENT, Tree.Kind.DO_STATEMENT, Tree.Kind.FOR_STATEMENT);
  }

  @Override
  public void visitNode(Tree tree) {
    ExpressionTree cond;
    StatementTree body;
    switch (tree) {
      case WhileStatementTree w -> {
        cond = w.condition();
        body = w.statement();
      }
      case DoWhileStatementTree d -> {
        cond = d.condition();
        body = d.statement();
      }
      case ForStatementTree f -> {
        cond = f.condition();
        body = f.statement();
      }
      default -> {
        return;
      }
    }
    boolean auth = Trees.any(body, MethodInvocationTree.class, m -> Trees.name(m).matches("(?i)(login|logIn|signIn|authenticate|authorize|auth|bind)"))
      || cond != null && Trees.any(cond, MethodInvocationTree.class, m -> Trees.name(m).matches("(?i)(login|logIn|signIn|authenticate|auth)"));
    if (!auth) {
      return;
    }
    boolean limited = (cond != null && Trees.any(cond, BinaryExpressionTree.class, UnboundedAuthRetryCheck::isRelational))
      || Trees.any(body, BinaryExpressionTree.class, UnboundedAuthRetryCheck::isRelational)
      || Trees.any(body, Tree.class, t -> t.is(Tree.Kind.THROW_STATEMENT));
    if (!limited) {
      reportIssue(tree.firstToken(), "Authentication is retried without a limit; count the attempts and stop or slow down after a few.");
    }
  }

  private static boolean isRelational(BinaryExpressionTree b) {
    return b.is(Tree.Kind.LESS_THAN, Tree.Kind.LESS_THAN_OR_EQUAL_TO, Tree.Kind.GREATER_THAN, Tree.Kind.GREATER_THAN_OR_EQUAL_TO);
  }
}
