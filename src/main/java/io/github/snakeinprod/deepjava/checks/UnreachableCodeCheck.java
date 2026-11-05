package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.CatchTree;
import org.sonar.plugins.java.api.tree.CompilationUnitTree;
import org.sonar.plugins.java.api.tree.ForStatementTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.IfStatementTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.TryStatementTree;
import org.sonar.plugins.java.api.tree.VariableTree;

/**
 * Reference parity mode only: a statement that no path reaches. Code after a call that always throws and
 * unreachable branches in Lombok-generated members (both from the bytecode), and the statement after a retry loop
 * with a constant bound whose every iteration returns or, on the last one, throws.
 */
@Rule(key = UnreachableCodeCheck.KEY)
public class UnreachableCodeCheck extends IssuableSubscriptionVisitor {

  public static final String KEY = "UnreachableCode";

  private final Supplier<Summaries> summaries;
  private final Set<Integer> lines = new HashSet<>();

  public UnreachableCodeCheck(Supplier<Summaries> summaries) {
    this.summaries = summaries;
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.COMPILATION_UNIT, Tree.Kind.FOR_STATEMENT);
  }

  @Override
  public void visitNode(Tree tree) {
    Summaries s = summaries.get();
    if (!s.options().referenceParity()) {
      return;
    }
    if (tree instanceof CompilationUnitTree cu) {
      lines.clear();
      String path = FindingCheck.path(cu, context);
      for (Summaries.Finding f : s.findings(FindingChecks.LombokUnreachable.KEY, path)) {
        if (FindingChecks.LombokUnreachable.onLombokLine(f, context.getFileLines()) && lines.add(f.line())) {
          addIssue(f.line(), f.message());
        }
      }
      for (Summaries.Finding f : s.findings(FindingChecks.CodeAfterThrow.KEY, path)) {
        if (lines.add(f.line())) {
          addIssue(f.line(), f.message());
        }
      }
      return;
    }
    ForStatementTree loop = (ForStatementTree) tree;
    if (loop.parent() instanceof BlockTree block && isRetryLoop(loop)) {
      List<StatementTree> after = Trees.statementsAfter(block, loop);
      if (!after.isEmpty() && lines.add(Trees.line(after.get(0)))) {
        reportIssue(after.get(0), "Every attempt of the loop above returns, and the last one throws: this code never runs.");
      }
    }
  }

  /**
   * {@code for (int i = 0; i < 3; i++) { try { return call(); } catch (E e) { if (i == 2) throw e; } }}: a constant
   * bound, no {@code break}, and a body that is one {@code try} whose block ends in a return and whose every catch
   * throws under a test of the counter.
   */
  static boolean isRetryLoop(ForStatementTree loop) {
    if (loop.initializer().size() != 1 || !(loop.initializer().get(0) instanceof VariableTree v) || v.initializer() == null
      || v.initializer().asConstant().isEmpty() || loop.condition() == null || !(Trees.skip(loop.condition()) instanceof
      org.sonar.plugins.java.api.tree.BinaryExpressionTree cond) || Trees.skip(cond.rightOperand()).asConstant().isEmpty()
      && !isConstantField(cond.rightOperand()) || !v.symbol().equals(Trees.symbol(cond.leftOperand()))) {
      return false;
    }
    Symbol counter = v.symbol();
    List<StatementTree> body = loop.statement() instanceof BlockTree b ? b.body() : List.of(loop.statement());
    if (body.size() != 1 || !(body.get(0) instanceof TryStatementTree t) || t.catches().isEmpty() || t.finallyBlock() != null
      || Trees.any(loop.statement(), Tree.class, x -> x.is(Tree.Kind.BREAK_STATEMENT))) {
      return false;
    }
    List<StatementTree> tryBody = t.block().body();
    if (tryBody.isEmpty() || !tryBody.get(tryBody.size() - 1).is(Tree.Kind.RETURN_STATEMENT)) {
      return false;
    }
    for (CatchTree c : t.catches()) {
      boolean throwsOnLast = Trees.any(c.block(), IfStatementTree.class, i -> mentions(i.condition(), counter)
        && Trees.any(i.thenStatement(), Tree.class, x -> x.is(Tree.Kind.THROW_STATEMENT)));
      List<StatementTree> cb = c.block().body();
      boolean alwaysThrows = !cb.isEmpty() && cb.get(cb.size() - 1).is(Tree.Kind.THROW_STATEMENT);
      if (!throwsOnLast && !alwaysThrows) {
        return false;
      }
    }
    return true;
  }

  private static boolean isConstantField(org.sonar.plugins.java.api.tree.ExpressionTree e) {
    Symbol s = Trees.symbol(e);
    return s != null && s.isVariableSymbol() && s.isStatic() && s.isFinal();
  }

  private static boolean mentions(Tree t, Symbol var) {
    return Trees.any(t, IdentifierTree.class, id -> var.equals(id.symbol()));
  }
}
