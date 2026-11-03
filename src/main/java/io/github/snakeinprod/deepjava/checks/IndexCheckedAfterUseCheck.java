package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.ArrayAccessExpressionTree;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;

/** {@code a[i]} first, {@code if (i < a.length)} afterwards. */
@Rule(key = IndexCheckedAfterUseCheck.KEY)
public class IndexCheckedAfterUseCheck extends DeepCheck {

  public static final String KEY = "IndexCheckedAfterUse";

  public IndexCheckedAfterUseCheck(Supplier<Summaries> summaries) {
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
      StatementTree s = body.get(k);
      if (!s.is(Tree.Kind.EXPRESSION_STATEMENT, Tree.Kind.VARIABLE, Tree.Kind.RETURN_STATEMENT)) {
        continue;
      }
      for (ArrayAccessExpressionTree a : Trees.collect(s, ArrayAccessExpressionTree.class)) {
        check(body, k, s, a, a.dimension().expression());
      }
      for (MethodInvocationTree m : Trees.collect(s, MethodInvocationTree.class)) {
        if (List.of("get", "charAt").contains(Trees.name(m)) && m.arguments().size() == 1 && !m.methodSymbol().isUnknown()
          && m.methodSymbol().parameterTypes().get(0).is("int")) {
          check(body, k, s, m, m.arguments().get(0));
        }
      }
    }
  }

  private void check(List<StatementTree> body, int k, StatementTree s, Tree use, ExpressionTree index) {
    Symbol i = Trees.symbol(index);
    if (i == null || Trees.skip(index) != index || Trees.isConditional(use, s)) {
      return;
    }
    for (int j = k + 1; j < body.size(); j++) {
      StatementTree later = body.get(j);
      for (BinaryExpressionTree b : Trees.collect(later, BinaryExpressionTree.class)) {
        if (b.is(Tree.Kind.LESS_THAN, Tree.Kind.LESS_THAN_OR_EQUAL_TO, Tree.Kind.GREATER_THAN, Tree.Kind.GREATER_THAN_OR_EQUAL_TO)
          && (i.equals(Trees.symbol(b.leftOperand())) && isBound(b.rightOperand()) || i.equals(Trees.symbol(b.rightOperand())) && isBound(b.leftOperand()))) {
          reportIssue(use, "\"" + i.name() + "\" is used as an index here and only checked against the bounds at line " + Trees.line(b) + ".");
          return;
        }
      }
      if (Trees.assigns(later, i)) {
        return;
      }
    }
  }

  private static boolean isBound(ExpressionTree e) {
    e = Trees.skip(e);
    if (e instanceof org.sonar.plugins.java.api.tree.MemberSelectExpressionTree ms) {
      return "length".equals(ms.identifier().name());
    }
    if (e instanceof MethodInvocationTree m) {
      return List.of("size", "length").contains(Trees.name(m)) && m.arguments().isEmpty();
    }
    return Integer.valueOf(0).equals(Trees.intConstant(e));
  }
}
