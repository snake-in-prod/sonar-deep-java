package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;

/** {@code total / list.size()} with no check that the size is not 0. */
@Rule(key = DivisionByPossibleZeroCheck.KEY)
public class DivisionByPossibleZeroCheck extends DeepCheck {

  public static final String KEY = "DivisionByPossibleZero";

  private static final String COUNT_CALL = "size|length|count|getCount|getSize|getLength|getTotal";

  public DivisionByPossibleZeroCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.DIVIDE, Tree.Kind.REMAINDER);
  }

  @Override
  public void visitNode(Tree tree) {
    BinaryExpressionTree b = (BinaryExpressionTree) tree;
    if (!b.symbolType().isPrimitive() || b.symbolType().is("double") || b.symbolType().is("float")) {
      return;
    }
    ExpressionTree divisor = Trees.skip(b.rightOperand());
    ExpressionTree count = countOf(divisor);
    if (count == null) {
      return;
    }
    Tree method = Trees.enclosingMethod(b);
    if (method == null || isChecked(method, divisor, count, b)) {
      return;
    }
    reportIssue(b.rightOperand(), "This divisor can be 0 (an empty collection or a zero count); check it before dividing.");
  }

  /** The count expression behind the divisor: {@code x.size()}, or a local initialised with one. */
  private static ExpressionTree countOf(ExpressionTree e) {
    if (e instanceof MethodInvocationTree m && m.arguments().isEmpty() && Trees.name(m).matches(COUNT_CALL)) {
      return m;
    }
    Symbol s = Trees.symbol(e);
    if (Trees.isLocal(s) && s.declaration() instanceof VariableTree v && v.initializer() != null
      && Trees.skip(v.initializer()) instanceof MethodInvocationTree m && m.arguments().isEmpty() && Trees.name(m).matches(COUNT_CALL)) {
      return m;
    }
    return null;
  }

  /** A comparison of the divisor or the count, or an isEmpty() on the counted object, anywhere in the method. */
  private static boolean isChecked(Tree method, ExpressionTree divisor, ExpressionTree count, Tree division) {
    ExpressionTree counted = count instanceof MethodInvocationTree m ? Trees.receiver(m) : null;
    for (BinaryExpressionTree c : Trees.collect(method, BinaryExpressionTree.class)) {
      if (c != division && c.is(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO, Tree.Kind.GREATER_THAN, Tree.Kind.GREATER_THAN_OR_EQUAL_TO,
        Tree.Kind.LESS_THAN, Tree.Kind.LESS_THAN_OR_EQUAL_TO)
        && (refersTo(c.leftOperand(), divisor, count) || refersTo(c.rightOperand(), divisor, count))) {
        return true;
      }
    }
    for (MethodInvocationTree m : Trees.collect(method, MethodInvocationTree.class)) {
      if (counted != null && List.of("isEmpty", "max", "checkArgument", "requirePositive").contains(Trees.name(m))
        && (Trees.receiver(m) != null && Trees.same(Trees.receiver(m), counted) || m.arguments().stream().anyMatch(a -> refersTo(a, divisor, count)))) {
        return true;
      }
    }
    return false;
  }

  private static boolean refersTo(ExpressionTree e, ExpressionTree divisor, ExpressionTree count) {
    e = Trees.skip(e);
    return Trees.same(e, divisor) || Trees.same(e, count)
      || e instanceof IdentifierTree id && divisor instanceof IdentifierTree d && id.symbol().equals(d.symbol())
      || e instanceof MemberSelectExpressionTree ms && divisor instanceof MemberSelectExpressionTree md && Trees.same(ms, md);
  }
}
