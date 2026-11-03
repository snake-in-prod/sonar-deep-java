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
    List<List<BinaryExpressionTree>> comparisons = new java.util.ArrayList<>();
    for (StatementTree st : body) {
      comparisons.add(Trees.collect(st, BinaryExpressionTree.class).stream()
        .filter(b -> b.is(Tree.Kind.LESS_THAN, Tree.Kind.LESS_THAN_OR_EQUAL_TO, Tree.Kind.GREATER_THAN, Tree.Kind.GREATER_THAN_OR_EQUAL_TO)).toList());
    }
    List<java.util.Set<Symbol>> assigned = Trees.assignedPerStatement(body);
    for (int k = 0; k < body.size(); k++) {
      StatementTree s = body.get(k);
      if (!s.is(Tree.Kind.EXPRESSION_STATEMENT, Tree.Kind.VARIABLE, Tree.Kind.RETURN_STATEMENT)) {
        continue;
      }
      for (ArrayAccessExpressionTree a : Trees.collect(s, ArrayAccessExpressionTree.class)) {
        check(body, comparisons, assigned, k, s, a, a.dimension().expression(), a.expression());
      }
      for (MethodInvocationTree m : Trees.collect(s, MethodInvocationTree.class)) {
        if (List.of("get", "charAt").contains(Trees.name(m)) && m.arguments().size() == 1 && !m.methodSymbol().isUnknown()
          && m.methodSymbol().parameterTypes().get(0).is("int")) {
          check(body, comparisons, assigned, k, s, m, m.arguments().get(0), Trees.receiver(m));
        }
      }
    }
  }

  private void check(List<StatementTree> body, List<List<BinaryExpressionTree>> comparisons, List<java.util.Set<Symbol>> assigned, int k,
    StatementTree s, Tree use, ExpressionTree index, ExpressionTree container) {
    Symbol i = Trees.symbol(index);
    if (i == null || container == null || Trees.skip(index) != index || Trees.isConditional(use, s) || boundedByLoop(s, i)) {
      return;
    }
    for (int j = k + 1; j < body.size(); j++) {
      for (BinaryExpressionTree b : comparisons.get(j)) {
        if (i.equals(Trees.symbol(b.leftOperand())) && isBound(b.rightOperand(), container)
          || i.equals(Trees.symbol(b.rightOperand())) && isBound(b.leftOperand(), container)) {
          reportIssue(use, "\"" + i.name() + "\" is used as an index here and only checked against the bounds at line " + Trees.line(b) + ".");
          return;
        }
      }
      if (assigned.get(j).contains(i)) {
        return;
      }
    }
  }

  /** {@code container.length}, {@code container.size()} or {@code container.length()}. */
  private static boolean isBound(ExpressionTree e, ExpressionTree container) {
    e = Trees.skip(e);
    if (e instanceof org.sonar.plugins.java.api.tree.MemberSelectExpressionTree ms) {
      return "length".equals(ms.identifier().name()) && Trees.same(ms.expression(), container);
    }
    if (e instanceof MethodInvocationTree m) {
      return List.of("size", "length").contains(Trees.name(m)) && m.arguments().isEmpty() && Trees.receiver(m) != null
        && Trees.same(Trees.receiver(m), container);
    }
    return false;
  }

  /** A loop around the use whose condition already bounds the index. */
  private static boolean boundedByLoop(Tree t, Symbol i) {
    for (Tree p = t.parent(); p != null && !p.is(Tree.Kind.METHOD, Tree.Kind.LAMBDA_EXPRESSION); p = p.parent()) {
      ExpressionTree cond = p instanceof org.sonar.plugins.java.api.tree.ForStatementTree f ? f.condition()
        : p instanceof org.sonar.plugins.java.api.tree.WhileStatementTree w ? w.condition() : null;
      if (cond != null && Trees.any(cond, ExpressionTree.class, e -> i.equals(Trees.symbol(e)))) {
        return true;
      }
    }
    return false;
  }
}
