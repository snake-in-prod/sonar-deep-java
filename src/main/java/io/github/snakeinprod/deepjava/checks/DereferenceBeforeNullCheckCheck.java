package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    List<StatementTree> body = ((BlockTree) tree).body();
    // per statement, computed once: null comparisons by variable, and assigned variables
    List<Map<Symbol, BinaryExpressionTree>> nullChecks = new ArrayList<>();
    for (StatementTree st : body) {
      Map<Symbol, BinaryExpressionTree> m = new HashMap<>();
      for (BinaryExpressionTree b : Trees.collect(st, BinaryExpressionTree.class)) {
        if (b.is(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO)) {
          ExpressionTree other = b.leftOperand().is(Tree.Kind.NULL_LITERAL) ? b.rightOperand()
            : b.rightOperand().is(Tree.Kind.NULL_LITERAL) ? b.leftOperand() : null;
          Symbol v = other == null ? null : Trees.symbol(other);
          if (v != null) {
            m.putIfAbsent(v, b);
          }
        }
      }
      nullChecks.add(m);
    }
    List<Set<Symbol>> assigned = Trees.assignedPerStatement(body);
    for (int k = 0; k < body.size(); k++) {
      StatementTree s = body.get(k);
      if (!s.is(Tree.Kind.EXPRESSION_STATEMENT, Tree.Kind.VARIABLE)) {
        continue;
      }
      Map<Symbol, Integer> derefs = new HashMap<>();
      for (MemberSelectExpressionTree ms : Trees.collect(s, MemberSelectExpressionTree.class)) {
        dereference(s, ms.expression(), Trees.line(ms), derefs);
      }
      for (ArrayAccessExpressionTree aa : Trees.collect(s, ArrayAccessExpressionTree.class)) {
        dereference(s, aa.expression(), Trees.line(aa), derefs);
      }
      derefs.keySet().removeAll(assigned.get(k));
      for (Map.Entry<Symbol, Integer> d : derefs.entrySet()) {
        for (int j = k + 1; j < body.size(); j++) {
          BinaryExpressionTree b = nullChecks.get(j).get(d.getKey());
          if (b != null) {
            reportIssue(b, "\"" + d.getKey().name() + "\" was already dereferenced at line " + d.getValue()
              + ", so this null check is dead or the dereference is wrong.");
            break;
          }
          if (assigned.get(j).contains(d.getKey())) {
            break;
          }
        }
      }
    }
  }

  private static void dereference(StatementTree s, ExpressionTree derefd, int line, Map<Symbol, Integer> out) {
    Symbol var = Trees.symbol(derefd);
    if (Trees.isLocal(var) && Trees.skip(derefd) == derefd && !Trees.isConditional(derefd, s) && !(s.is(Tree.Kind.VARIABLE) && var.declaration() == s)) {
      out.putIfAbsent(var, line);
    }
  }
}
