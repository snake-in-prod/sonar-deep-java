package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.ConditionalExpressionTree;
import org.sonar.plugins.java.api.tree.Tree;

/** {@code x == y ? 1 : 0 + z}: the arithmetic only applies to the false branch. */
@Rule(key = TernaryPrecedenceCheck.KEY)
public class TernaryPrecedenceCheck extends DeepCheck {

  public static final String KEY = "TernaryPrecedence";

  public TernaryPrecedenceCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.CONDITIONAL_EXPRESSION);
  }

  @Override
  public void visitNode(Tree tree) {
    ConditionalExpressionTree c = (ConditionalExpressionTree) tree;
    if (c.condition() instanceof BinaryExpressionTree cond && cond.is(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO)
      && (cond.leftOperand().is(Tree.Kind.PLUS, Tree.Kind.MINUS) || cond.rightOperand().is(Tree.Kind.PLUS, Tree.Kind.MINUS))
      && c.trueExpression().asConstant().isPresent() && c.falseExpression().asConstant().isPresent()) {
      reportIssue(cond, "Arithmetic, comparison and \"?:\" are mixed without parentheses; add them to show the intended order.");
    }
    if (c.falseExpression() instanceof BinaryExpressionTree b && b.is(Tree.Kind.PLUS, Tree.Kind.MINUS, Tree.Kind.MULTIPLY)
      && b.leftOperand().is(Tree.Kind.INT_LITERAL, Tree.Kind.LONG_LITERAL) && c.trueExpression().is(Tree.Kind.INT_LITERAL, Tree.Kind.LONG_LITERAL)
      && !b.rightOperand().is(Tree.Kind.INT_LITERAL, Tree.Kind.LONG_LITERAL)) {
      reportIssue(b, "\"" + b.operatorToken().text() + "\" only applies to the false branch; add parentheses around the ternary "
        + "if it should apply to both.");
    }
  }
}
