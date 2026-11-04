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

  private static final Tree.Kind[] ARITHMETIC = {Tree.Kind.PLUS, Tree.Kind.MINUS, Tree.Kind.MULTIPLY, Tree.Kind.DIVIDE, Tree.Kind.REMAINDER};
  private static final Tree.Kind[] COMPARISON = {Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO, Tree.Kind.LESS_THAN, Tree.Kind.LESS_THAN_OR_EQUAL_TO,
    Tree.Kind.GREATER_THAN, Tree.Kind.GREATER_THAN_OR_EQUAL_TO};

  /**
   * An unparenthesised comparison of arithmetic as the condition: {@code a == b + 1 ? 1 : 0} (constant branches),
   * {@code a + b - 1 < c ? x : y} (two operators) or {@code x % 2 == 0 ? a + 1 : b} (remainder).
   */
  private static boolean isMixed(BinaryExpressionTree cond, ConditionalExpressionTree c) {
    if (!cond.is(COMPARISON) || cond.symbolType().isUnknown() || isString(cond.leftOperand()) || isString(cond.rightOperand())) {
      return false;
    }
    for (var operand : List.of(cond.leftOperand(), cond.rightOperand())) {
      if (!(operand instanceof BinaryExpressionTree a) || !a.is(ARITHMETIC)) {
        continue;
      }
      boolean constants = cond.is(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO) && a.is(Tree.Kind.PLUS, Tree.Kind.MINUS)
        && c.trueExpression().asConstant().isPresent() && c.falseExpression().asConstant().isPresent();
      boolean twoOperators = a.leftOperand() instanceof BinaryExpressionTree l && l.is(ARITHMETIC)
        || a.rightOperand() instanceof BinaryExpressionTree r && r.is(ARITHMETIC);
      if (constants || twoOperators || a.is(Tree.Kind.REMAINDER)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isString(org.sonar.plugins.java.api.tree.ExpressionTree e) {
    return e.symbolType().is("java.lang.String");
  }

  @Override
  public void visitNode(Tree tree) {
    ConditionalExpressionTree c = (ConditionalExpressionTree) tree;
    if (c.condition() instanceof BinaryExpressionTree cond && isMixed(cond, c)) {
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
