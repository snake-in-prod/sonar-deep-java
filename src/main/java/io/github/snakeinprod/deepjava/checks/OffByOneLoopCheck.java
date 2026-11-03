package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.ArrayAccessExpressionTree;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.ForStatementTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;

/** {@code for (i = 0; i <= a.length; i++) a[i]}: the last iteration is out of bounds. */
@Rule(key = OffByOneLoopCheck.KEY)
public class OffByOneLoopCheck extends DeepCheck {

  public static final String KEY = "OffByOneLoopBound";

  public OffByOneLoopCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.FOR_STATEMENT);
  }

  @Override
  public void visitNode(Tree tree) {
    ForStatementTree f = (ForStatementTree) tree;
    if (!(f.condition() instanceof BinaryExpressionTree b) || !b.is(Tree.Kind.LESS_THAN_OR_EQUAL_TO)) {
      return;
    }
    Symbol i = Trees.symbol(b.leftOperand());
    ExpressionTree container = container(b.rightOperand());
    if (i == null || container == null) {
      return;
    }
    boolean indexed = Trees.any(f.statement(), ArrayAccessExpressionTree.class,
      a -> i.equals(Trees.symbol(a.dimension().expression())) && Trees.same(a.expression(), container))
      || Trees.any(f.statement(), MethodInvocationTree.class, m -> Trees.receiver(m) != null && Trees.same(Trees.receiver(m), container)
        && List.of("get", "charAt", "set").contains(Trees.name(m)) && !m.arguments().isEmpty() && i.equals(Trees.symbol(m.arguments().get(0))));
    if (indexed) {
      reportIssue(b, "\"" + i.name() + "\" reaches the length here, one past the last valid index; use \"<\".");
    }
  }

  /** {@code a} of {@code a.length}, {@code a.size()} or {@code a.length()}. */
  private static ExpressionTree container(ExpressionTree e) {
    e = Trees.skip(e);
    if (e instanceof MemberSelectExpressionTree ms && "length".equals(ms.identifier().name()) && ms.expression().symbolType().isArray()) {
      return ms.expression();
    }
    if (e instanceof MethodInvocationTree m && m.arguments().isEmpty() && List.of("size", "length").contains(Trees.name(m))) {
      return Trees.receiver(m);
    }
    return null;
  }
}
