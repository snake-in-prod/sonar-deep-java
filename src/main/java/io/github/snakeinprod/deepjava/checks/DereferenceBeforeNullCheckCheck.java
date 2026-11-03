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
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;

/** {@code x.foo(); ... if (x == null)}: the check is dead, or the dereference is the bug. */
@Rule(key = DereferenceBeforeNullCheckCheck.KEY)
public class DereferenceBeforeNullCheckCheck extends DeepCheck {

  public static final String KEY = "DereferenceBeforeNullCheck";

  public DereferenceBeforeNullCheckCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.BLOCK);
  }

  @Override
  public void visitNode(Tree tree) {
    BlockTree block = (BlockTree) tree;
    List<StatementTree> body = block.body();
    for (int k = 0; k < body.size(); k++) {
      StatementTree s = body.get(k);
      if (!s.is(Tree.Kind.EXPRESSION_STATEMENT, Tree.Kind.VARIABLE)) {
        continue;
      }
      for (MemberSelectExpressionTree ms : Trees.collect(s, MemberSelectExpressionTree.class)) {
        check(body, k, s, ms.expression(), Trees.line(ms));
      }
      for (ArrayAccessExpressionTree aa : Trees.collect(s, ArrayAccessExpressionTree.class)) {
        check(body, k, s, aa.expression(), Trees.line(aa));
      }
    }
  }

  private void check(List<StatementTree> body, int k, StatementTree s, ExpressionTree derefd, int line) {
    Symbol var = Trees.symbol(derefd);
    if (!Trees.isLocal(var) || Trees.skip(derefd) != derefd || Trees.isConditional(derefd, s)) {
      return;
    }
    if (s.is(Tree.Kind.VARIABLE) && var.declaration() == s) {
      return;
    }
    for (int j = k + 1; j < body.size(); j++) {
      StatementTree later = body.get(j);
      for (BinaryExpressionTree b : Trees.collect(later, BinaryExpressionTree.class)) {
        if (b.is(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO)) {
          ExpressionTree other = b.leftOperand().is(Tree.Kind.NULL_LITERAL) ? b.rightOperand()
            : b.rightOperand().is(Tree.Kind.NULL_LITERAL) ? b.leftOperand() : null;
          if (other != null && var.equals(Trees.symbol(other))) {
            reportIssue(b, "\"" + var.name() + "\" was already dereferenced at line " + line
              + ", so this null check is dead or the dereference is wrong.");
            return;
          }
        }
      }
      if (Trees.assigns(later, var)) {
        return;
      }
    }
  }
}
