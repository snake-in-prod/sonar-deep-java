package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.semantic.Type;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.ForStatementTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.Tree;

/** A loop counter that can never reach its bound, so it overflows and the loop never ends. */
@Rule(key = LoopCounterOverflowCheck.KEY)
public class LoopCounterOverflowCheck extends DeepCheck {

  public static final String KEY = "LoopCounterOverflow";

  public LoopCounterOverflowCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.FOR_STATEMENT);
  }

  @Override
  public void visitNode(Tree tree) {
    ForStatementTree f = (ForStatementTree) tree;
    if (!(f.condition() instanceof BinaryExpressionTree b) || !b.is(Tree.Kind.LESS_THAN, Tree.Kind.LESS_THAN_OR_EQUAL_TO)) {
      return;
    }
    Symbol i = Trees.symbol(b.leftOperand());
    if (i == null || f.update().isEmpty() || f.update().stream().noneMatch(u -> Trees.assigns(u, i))) {
      return;
    }
    Type type = i.type();
    boolean le = b.is(Tree.Kind.LESS_THAN_OR_EQUAL_TO);
    ExpressionTree bound = Trees.skip(b.rightOperand());
    if (le && bound instanceof MemberSelectExpressionTree ms && "MAX_VALUE".equals(ms.identifier().name())
      && (ms.expression().symbolType().is("java.lang.Integer") && type.is("int") || ms.expression().symbolType().is("java.lang.Long") && type.is("long"))) {
      reportIssue(b, "\"" + i.name() + "\" is always <= MAX_VALUE: it overflows and the loop never ends.");
      return;
    }
    Integer c = Trees.intConstant(bound);
    long max = type.is("byte") ? Byte.MAX_VALUE : type.is("short") ? Short.MAX_VALUE : type.is("char") ? Character.MAX_VALUE : -1;
    if (c != null && max > 0 && (le ? c >= max : c > max)) {
      reportIssue(b, "A " + type.name() + " never reaches " + c + " (max " + max + "): \"" + i.name() + "\" overflows and the loop never ends.");
    }
  }
}
