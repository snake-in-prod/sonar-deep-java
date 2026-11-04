package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.ArrayAccessExpressionTree;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;

/** {@code if (...) { f = null; } ... f.getName();}: null set on one path (also in a catch) and dereferenced after the join. */
@Rule(key = NullAssignedThenDereferencedCheck.KEY)
public class NullAssignedThenDereferencedCheck extends DeepCheck {

  public static final String KEY = "NullAssignedThenDereferenced";

  public NullAssignedThenDereferencedCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.BLOCK);
  }

  @Override
  public void visitNode(Tree tree) {
    List<StatementTree> body = ((BlockTree) tree).body();
    List<Set<Symbol>> assigned = null;
    for (int k = 0; k < body.size(); k++) {
      StatementTree s = body.get(k);
      if (!s.is(Tree.Kind.IF_STATEMENT, Tree.Kind.TRY_STATEMENT, Tree.Kind.SWITCH_STATEMENT)) {
        continue;
      }
      for (AssignmentExpressionTree a : Trees.collect(s, AssignmentExpressionTree.class)) {
        Symbol var = Trees.symbol(a.variable());
        if (!a.is(Tree.Kind.ASSIGNMENT) || !a.expression().is(Tree.Kind.NULL_LITERAL) || !Trees.isLocal(var) || !flowsOut(a, var)) {
          continue;
        }
        if (assigned == null) {
          assigned = Trees.assignedPerStatement(body);
        }
        checkAfter(body, assigned, k, var, Trees.line(a));
      }
    }
  }

  /** After {@code var = null} its block neither leaves (return, throw, break, continue) nor assigns var again. */
  private static boolean flowsOut(AssignmentExpressionTree a, Symbol var) {
    Tree stmt = a.parent();
    if (!(stmt instanceof StatementTree st) || !(stmt.parent() instanceof BlockTree block)) {
      return false;
    }
    for (StatementTree after : Trees.statementsAfter(block, st)) {
      if (after.is(Tree.Kind.RETURN_STATEMENT, Tree.Kind.THROW_STATEMENT, Tree.Kind.BREAK_STATEMENT, Tree.Kind.CONTINUE_STATEMENT)
        || Trees.assigns(after, var)) {
        return false;
      }
    }
    return true;
  }

  private void checkAfter(List<StatementTree> body, List<Set<Symbol>> assigned, int k, Symbol var, int line) {
    for (int j = k + 1; j < body.size(); j++) {
      StatementTree later = body.get(j);
      boolean checked = Trees.any(later, BinaryExpressionTree.class, b -> b.is(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO)
        && (b.leftOperand().is(Tree.Kind.NULL_LITERAL) && var.equals(Trees.symbol(b.rightOperand()))
          || b.rightOperand().is(Tree.Kind.NULL_LITERAL) && var.equals(Trees.symbol(b.leftOperand()))));
      if (checked) {
        return;
      }
      for (Tree t : Trees.collect(later, Tree.class)) {
        ExpressionTree derefd = t instanceof MemberSelectExpressionTree ms ? ms.expression()
          : t instanceof ArrayAccessExpressionTree aa ? aa.expression() : null;
        if (derefd != null && var.equals(Trees.symbol(derefd)) && Trees.skip(derefd) == derefd && !Trees.isConditional(t, later)
          && !(t instanceof MemberSelectExpressionTree m && m.parent() instanceof AssignmentExpressionTree as && as.variable() == m)) {
          reportIssue(t, "\"" + var.name() + "\" is set to null at line " + line + " on some path and dereferenced here without a check.");
          return;
        }
      }
      if (assigned.get(j).contains(var)) {
        return;
      }
    }
  }
}
