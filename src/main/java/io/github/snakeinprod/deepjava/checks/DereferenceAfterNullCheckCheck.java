package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.ConditionalExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.IfStatementTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;

/**
 * Reference parity mode only: a value compared to null and then dereferenced on the path where it is null
 * ({@code if (x == null) { log(x.getName()); }}, {@code x == null && x.isEmpty()}).
 */
@Rule(key = DereferenceAfterNullCheckCheck.KEY)
public class DereferenceAfterNullCheckCheck extends DeepCheck {

  public static final String KEY = "DereferenceAfterNullCheck";

  public DereferenceAfterNullCheckCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.IF_STATEMENT, Tree.Kind.CONDITIONAL_AND, Tree.Kind.CONDITIONAL_OR, Tree.Kind.CONDITIONAL_EXPRESSION);
  }

  /** The variable tested and whether the test is true when it is null. */
  private record NullTest(Symbol var, boolean nullWhenTrue) {
  }

  @Override
  public void visitNode(Tree tree) {
    if (!summaries.get().options().referenceParity()) {
      return;
    }
    if (tree instanceof IfStatementTree i) {
      NullTest t = nullTest(i.condition());
      Tree branch = t == null ? null : t.nullWhenTrue() ? i.thenStatement() : i.elseStatement();
      check(t, branch);
    } else if (tree instanceof ConditionalExpressionTree c) {
      NullTest t = nullTest(c.condition());
      check(t, t == null ? null : t.nullWhenTrue() ? c.trueExpression() : c.falseExpression());
    } else {
      BinaryExpressionTree b = (BinaryExpressionTree) tree;
      NullTest t = nullTest(b.leftOperand());
      // x == null && x.m(): the right side runs when x is null; x != null || x.m() likewise
      if (t != null && t.nullWhenTrue() == b.is(Tree.Kind.CONDITIONAL_AND)) {
        check(t, b.rightOperand());
      }
    }
  }

  private static NullTest nullTest(ExpressionTree cond) {
    ExpressionTree e = Trees.skip(cond);
    if (!(e instanceof BinaryExpressionTree b) || !b.is(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO)) {
      return null;
    }
    ExpressionTree other = b.leftOperand().is(Tree.Kind.NULL_LITERAL) ? b.rightOperand()
      : b.rightOperand().is(Tree.Kind.NULL_LITERAL) ? b.leftOperand() : null;
    Symbol var = other == null || !(Trees.skip(other) instanceof IdentifierTree id) ? null : id.symbol();
    if (var == null || var.isUnknown() || !var.isVariableSymbol()) {
      return null;
    }
    return new NullTest(var, b.is(Tree.Kind.EQUAL_TO));
  }

  /** The first use of the variable in the null branch, if it is a dereference. */
  private void check(NullTest t, Tree branch) {
    if (t == null || branch == null) {
      return;
    }
    List<IdentifierTree> uses = new ArrayList<>(Trees.collect(branch, IdentifierTree.class).stream().filter(id -> t.var().equals(id.symbol())).toList());
    if (uses.isEmpty()) {
      return;
    }
    uses.sort((a, b) -> a.identifierToken().range().start().compareTo(b.identifierToken().range().start()));
    IdentifierTree first = uses.get(0);
    Tree p = Derefs.parent(first);
    if (p instanceof AssignmentExpressionTree a && a.variable() == first || !Derefs.isDereference(p, first, Derefs.isBoxed(t.var().type()))) {
      return;
    }
    // a field may be set by a call made before the dereference
    if (Trees.isField(t.var()) && Trees.collect(branch, MethodInvocationTree.class).stream()
      .anyMatch(m -> m.firstToken().range().start().compareTo(first.identifierToken().range().start()) < 0 && !contains(m, first))) {
      return;
    }
    reportIssue(first, "\"" + t.var().name() + "\" is null here: it was just compared to null.");
  }

  private static boolean contains(Tree outer, Tree inner) {
    for (Tree p = inner; p != null; p = p.parent()) {
      if (p == outer) {
        return true;
      }
    }
    return false;
  }
}
