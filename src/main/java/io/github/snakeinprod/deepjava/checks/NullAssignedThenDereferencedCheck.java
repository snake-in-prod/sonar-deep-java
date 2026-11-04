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

  /** Nested {@code if}s see the same assignment from each enclosing block: report a dereference once. */
  private final Set<Tree> reported = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

  @Override
  public void setContext(org.sonar.plugins.java.api.JavaFileScannerContext context) {
    reported.clear();
    super.setContext(context);
  }

  public NullAssignedThenDereferencedCheck(Supplier<Summaries> summaries) {
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
    List<Set<Symbol>> assigned = null;
    for (int k = 0; k < body.size(); k++) {
      StatementTree s = body.get(k);
      if (s instanceof org.sonar.plugins.java.api.tree.VariableTree v && v.initializer() != null
        && v.initializer().is(Tree.Kind.NULL_LITERAL) && Trees.isLocal(v.symbol())) {
        if (assigned == null) {
          assigned = Trees.assignedPerStatement(body);
        }
        declaredNull(body, assigned, k, v);
        continue;
      }
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
        if (!checkAfter(body, assigned, k, var, Trees.line(a))) {
          checkEnclosing(block, var, Trees.line(a));
        }
      }
    }
  }

  /**
   * {@code String name = null; if (c) { name = x; } ... name.trim()}: assigned on some paths only, then dereferenced.
   * A dereference with no assignment at all in between is left to Sonar's own null rule.
   */
  private void declaredNull(List<StatementTree> body, List<Set<Symbol>> assigned, int k, org.sonar.plugins.java.api.tree.VariableTree v) {
    Symbol var = v.symbol();
    for (int j = k + 1; j < body.size(); j++) {
      StatementTree st = body.get(j);
      if (!assigned.get(j).contains(var)) {
        if (Trees.any(st, org.sonar.plugins.java.api.tree.IdentifierTree.class, id -> var.equals(id.symbol()))) {
          return; // used before any assignment: not this pattern
        }
        continue;
      }
      // a loop that assigns usually runs until it has (or leaves the method): parity mode only
      if (assignsOnEveryPath(st, var) || st.is(Tree.Kind.FOR_STATEMENT, Tree.Kind.FOR_EACH_STATEMENT, Tree.Kind.WHILE_STATEMENT,
        Tree.Kind.DO_STATEMENT) && !summaries.get().options().referenceParity()) {
        return;
      }
      checkAfter(body, assigned, j, var, Trees.line(v));
      return;
    }
  }

  private static boolean assignsOnEveryPath(StatementTree st, Symbol var) {
    if (st instanceof org.sonar.plugins.java.api.tree.ExpressionStatementTree) {
      return true;
    }
    if (st instanceof org.sonar.plugins.java.api.tree.IfStatementTree i) {
      return i.elseStatement() != null && Trees.assigns(i.thenStatement(), var) && (Trees.assigns(i.elseStatement(), var)
        || i.elseStatement() instanceof BlockTree b && b.body().stream().anyMatch(x -> x.is(Tree.Kind.THROW_STATEMENT, Tree.Kind.RETURN_STATEMENT)));
    }
    if (st instanceof org.sonar.plugins.java.api.tree.TryStatementTree t) {
      return t.catches().stream().allMatch(c -> Trees.assigns(c.block(), var)
        || c.block().body().stream().anyMatch(x -> x.is(Tree.Kind.THROW_STATEMENT, Tree.Kind.RETURN_STATEMENT)));
    }
    return false;
  }

  /** Nothing decided in the block: go on after the statement that encloses it ({@code if}, loop, {@code try}). */
  private void checkEnclosing(BlockTree block, Symbol var, int line) {
    Tree t = block;
    while (t.parent() != null && !(t.parent() instanceof BlockTree)) {
      t = t.parent();
      if (t.is(Tree.Kind.METHOD, Tree.Kind.CONSTRUCTOR, Tree.Kind.LAMBDA_EXPRESSION, Tree.Kind.CLASS)) {
        return;
      }
    }
    if (!(t.parent() instanceof BlockTree outer) || !(t instanceof StatementTree st)) {
      return;
    }
    List<StatementTree> body = outer.body();
    if (!checkAfter(body, Trees.assignedPerStatement(body), body.indexOf(st), var, line)) {
      checkEnclosing(outer, var, line);
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

  /** Whether a later statement decides the case: a null check, a dereference (reported) or a new assignment. */
  private boolean checkAfter(List<StatementTree> body, List<Set<Symbol>> assigned, int k, Symbol var, int line) {
    for (int j = k + 1; j < body.size(); j++) {
      StatementTree later = body.get(j);
      boolean checked = Trees.any(later, BinaryExpressionTree.class, b -> b.is(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO)
        && (b.leftOperand().is(Tree.Kind.NULL_LITERAL) && var.equals(Trees.symbol(b.rightOperand()))
          || b.rightOperand().is(Tree.Kind.NULL_LITERAL) && var.equals(Trees.symbol(b.leftOperand()))));
      if (checked || later.is(Tree.Kind.RETURN_STATEMENT, Tree.Kind.THROW_STATEMENT) && !mentions(later, var)) {
        return true;
      }
      for (Tree t : Trees.collect(later, Tree.class)) {
        ExpressionTree derefd = t instanceof MemberSelectExpressionTree ms ? ms.expression()
          : t instanceof ArrayAccessExpressionTree aa ? aa.expression() : null;
        if (derefd != null && var.equals(Trees.symbol(derefd)) && Trees.skip(derefd) == derefd && !Trees.assigns(later, var)
          && !correlated(t, later, var)
          && !(t instanceof MemberSelectExpressionTree m && m.parent() instanceof AssignmentExpressionTree as && as.variable() == m)) {
          if (reported.add(t)) {
            reportIssue(t, "\"" + var.name() + "\" is set to null at line " + line + " on some path and dereferenced here without a check.");
          }
          return true;
        }
      }
      // parity: an assignment on some branches only leaves the null of the other paths
      if (assigned.get(j).contains(var) && (!summaries.get().options().referenceParity() || assignsOnEveryPath(later, var))) {
        return true;
      }
    }
    return false;
  }

  /**
   * The dereference only runs under a later condition ({@code if (forSer) x = null; ... if (forDeser) x.get()}): the
   * conditions are often correlated, which this rule cannot see through. Reported only in parity mode.
   */
  private boolean correlated(Tree deref, Tree stop, Symbol var) {
    if (summaries.get().options().referenceParity()) {
      return false;
    }
    for (Tree child = deref, p = deref.parent(); p != null && child != stop; child = p, p = p.parent()) {
      if (p instanceof org.sonar.plugins.java.api.tree.IfStatementTree i && child != i.condition()
        || p instanceof org.sonar.plugins.java.api.tree.ConditionalExpressionTree c && child != c.condition()
        || p.is(Tree.Kind.CONDITIONAL_AND, Tree.Kind.CONDITIONAL_OR) && child == ((BinaryExpressionTree) p).rightOperand()
        || p.is(Tree.Kind.CASE_GROUP)) {
        return true;
      }
    }
    return false;
  }

  private static boolean mentions(Tree t, Symbol var) {
    return Trees.any(t, org.sonar.plugins.java.api.tree.IdentifierTree.class, id -> var.equals(id.symbol()));
  }
}
