package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.semantic.Type;
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
    return List.of(Tree.Kind.COMPILATION_UNIT, Tree.Kind.FOR_STATEMENT, Tree.Kind.TRY_STATEMENT, Tree.Kind.CLASS);
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
    if (tree instanceof org.sonar.plugins.java.api.tree.ClassTree c) {
      noIncludedField(c);
      return;
    }
    if (tree instanceof TryStatementTree t) {
      catchNullChecks(t);
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

  /**
   * Lombok {@code @EqualsAndHashCode(onlyExplicitlyIncluded = true)} with no {@code @EqualsAndHashCode.Include}: the
   * generated equals() compares nothing, so its field comparisons never run.
   */
  private void noIncludedField(org.sonar.plugins.java.api.tree.ClassTree c) {
    for (var a : c.modifiers().annotations()) {
      if (!a.symbolType().is("lombok.EqualsAndHashCode")) {
        continue;
      }
      boolean explicit = a.arguments().stream().anyMatch(arg -> arg instanceof org.sonar.plugins.java.api.tree.AssignmentExpressionTree as
        && as.variable() instanceof IdentifierTree id && "onlyExplicitlyIncluded".equals(id.name())
        && Boolean.TRUE.equals(as.expression().asConstant().orElse(null)));
      boolean included = c.members().stream().flatMap(m -> m instanceof VariableTree v ? v.modifiers().annotations().stream()
          : m instanceof org.sonar.plugins.java.api.tree.MethodTree mt ? mt.modifiers().annotations().stream() : java.util.stream.Stream.empty())
        .anyMatch(x -> x.annotationType() instanceof org.sonar.plugins.java.api.tree.MemberSelectExpressionTree ms && "Include".equals(ms.identifier().name())
          && (ms.expression() instanceof org.sonar.plugins.java.api.tree.MemberSelectExpressionTree e && "EqualsAndHashCode".equals(e.identifier().name())
            || ms.expression() instanceof IdentifierTree e2 && "EqualsAndHashCode".equals(e2.name())));
      if (explicit && !included && lines.add(Trees.line(a))) {
        reportIssue(a, "No member is marked @EqualsAndHashCode.Include: the generated equals() compares nothing and every instance is equal.");
      }
    }
  }

  /**
   * {@code a = new FileOutputStream(p); b = new OutputStreamWriter(a);} in the try, {@code if (b != null)} in
   * {@code catch (IOException e)}: b is assigned only after the last statement that can throw the caught exception,
   * so it is still null there.
   */
  private void catchNullChecks(TryStatementTree t) {
    List<StatementTree> body = t.block().body();
    for (CatchTree c : t.catches()) {
      Type caught = c.parameter().type().symbolType();
      if (c.parameter().type().is(Tree.Kind.UNION_TYPE) || caught.isUnknown() || !caught.isSubtypeOf("java.lang.Exception")
        || caught.is("java.lang.Exception") || caught.isSubtypeOf("java.lang.RuntimeException")) {
        continue;
      }
      int lastThrow = -1;
      for (int i = 0; i < body.size(); i++) {
        if (mayThrow(body.get(i), caught)) {
          lastThrow = i;
        }
      }
      if (lastThrow < 0) {
        continue;
      }
      for (IfStatementTree ifs : Trees.collect(c.block(), IfStatementTree.class)) {
        if (Trees.skip(ifs.condition()) instanceof org.sonar.plugins.java.api.tree.BinaryExpressionTree b && b.is(Tree.Kind.NOT_EQUAL_TO)
          && Trees.skip(b.rightOperand()).is(Tree.Kind.NULL_LITERAL) && Trees.skip(b.leftOperand()) instanceof IdentifierTree id
          && stillNull(id.symbol(), body, lastThrow) && lines.add(Trees.line(ifs))) {
          reportIssue(ifs.condition(), "\"" + id.name() + "\" is assigned only after the last call that can throw \"" + caught.name()
            + "\", so it is still null here: this branch never runs.");
        }
      }
    }
  }

  /** A local declared as null outside the try, assigned only by {@code v = ...;} statements of the try at or after {@code from}. */
  private static boolean stillNull(Symbol v, List<StatementTree> body, int from) {
    if (!Trees.isLocal(v) || !(v.declaration() instanceof VariableTree d) || d.initializer() == null
      || !Trees.skip(d.initializer()).is(Tree.Kind.NULL_LITERAL)) {
      return false;
    }
    boolean assigned = false;
    for (IdentifierTree u : v.usages()) {
      if (u.parent() instanceof org.sonar.plugins.java.api.tree.AssignmentExpressionTree a && a.variable() == u) {
        int at = body.indexOf(a.parent());
        if (at < from) {
          return false;
        }
        assigned = true;
      }
    }
    return assigned;
  }

  /** A call, constructor or throw in {@code st} that can throw {@code caught} (or a super- or subtype); unknown calls can. */
  private static boolean mayThrow(Tree st, Type caught) {
    return Trees.any(st, Tree.class, x -> {
      Symbol.MethodSymbol m = x instanceof org.sonar.plugins.java.api.tree.MethodInvocationTree mi ? mi.methodSymbol()
        : x instanceof org.sonar.plugins.java.api.tree.NewClassTree nc ? nc.methodSymbol() : null;
      if (m != null) {
        return m.isUnknown() || m.thrownTypes().stream().anyMatch(e -> e.isSubtypeOf(caught) || caught.isSubtypeOf(e));
      }
      return x instanceof org.sonar.plugins.java.api.tree.ThrowStatementTree th
        && (th.expression().symbolType().isSubtypeOf(caught) || caught.isSubtypeOf(th.expression().symbolType()));
    });
  }

  private static boolean isConstantField(org.sonar.plugins.java.api.tree.ExpressionTree e) {
    Symbol s = Trees.symbol(e);
    return s != null && s.isVariableSymbol() && s.isStatic() && s.isFinal();
  }

  private static boolean mentions(Tree t, Symbol var) {
    return Trees.any(t, IdentifierTree.class, id -> var.equals(id.symbol()));
  }
}
