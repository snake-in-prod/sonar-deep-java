package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.ConditionalExpressionTree;
import org.sonar.plugins.java.api.tree.DoWhileStatementTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IfStatementTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.UnaryExpressionTree;
import org.sonar.plugins.java.api.tree.WhileStatementTree;

/** A condition that is constant because a non-overridable method always returns one constant, or a field never changes. */
@Rule(key = ConstantConditionCheck.KEY)
public class ConstantConditionCheck extends DeepCheck {

  public static final String KEY = "ConstantConditionFromCallee";

  public ConstantConditionCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.IF_STATEMENT, Tree.Kind.WHILE_STATEMENT, Tree.Kind.DO_STATEMENT, Tree.Kind.CONDITIONAL_EXPRESSION,
      Tree.Kind.FOR_STATEMENT);
  }

  /** {@code for (int i = 1; i <= RETRIES; i++)} with {@code RETRIES = 0}: the loop never runs. */
  private void zeroTimes(org.sonar.plugins.java.api.tree.ForStatementTree loop) {
    if (loop.initializer().size() != 1 || !(loop.initializer().get(0) instanceof org.sonar.plugins.java.api.tree.VariableTree v)
      || v.initializer() == null || !(loop.condition() instanceof BinaryExpressionTree c) || !(c.leftOperand() instanceof org.sonar.plugins.java.api.tree.IdentifierTree id)
      || !id.symbol().equals(v.symbol())) {
      return;
    }
    Integer start = v.initializer().asConstant(Integer.class).orElse(null);
    Integer bound = c.rightOperand().asConstant(Integer.class).orElse(null);
    if (start == null || bound == null) {
      return;
    }
    boolean runs = switch (c.kind()) {
      case LESS_THAN -> start < bound;
      case LESS_THAN_OR_EQUAL_TO -> start <= bound;
      case GREATER_THAN -> start > bound;
      case GREATER_THAN_OR_EQUAL_TO -> start >= bound;
      default -> true;
    };
    if (!runs) {
      reportIssue(c, "This loop never runs: \"" + v.simpleName().name() + "\" starts at " + start + " and the condition is false.");
    }
  }

  @Override
  public void visitNode(Tree tree) {
    if (tree instanceof org.sonar.plugins.java.api.tree.ForStatementTree loop) {
      zeroTimes(loop);
      return;
    }
    ExpressionTree cond = switch (tree) {
      case IfStatementTree i -> i.condition();
      case WhileStatementTree w -> w.condition();
      case DoWhileStatementTree d -> d.condition();
      case ConditionalExpressionTree c -> c.condition();
      default -> null;
    };
    if (cond == null) {
      return;
    }
    if (Trees.skip(cond).is(Tree.Kind.BOOLEAN_LITERAL) && tree instanceof IfStatementTree) {
      reportIssue(cond, "This condition is always " + ((org.sonar.plugins.java.api.tree.LiteralTree) Trees.skip(cond)).value()
        + ": the branch is dead code or unconditional.");
      return;
    }
    for (BinaryExpressionTree c : Trees.collect(cond, BinaryExpressionTree.class)) {
      Boolean always = charRange(c);
      if (always != null) {
        reportIssue(c, "This comparison is always " + always + ": a char is never below '\\u0000' or above '\\uffff'.");
      }
    }
    boolean negated = false;
    ExpressionTree e = Trees.skip(cond);
    while (e instanceof UnaryExpressionTree u && e.is(Tree.Kind.LOGICAL_COMPLEMENT)) {
      negated = !negated;
      e = Trees.skip(u.expression());
    }
    if (e instanceof BinaryExpressionTree b && b.is(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO)
      && (b.leftOperand().is(Tree.Kind.NULL_LITERAL) || b.rightOperand().is(Tree.Kind.NULL_LITERAL))) {
      ExpressionTree other = b.leftOperand().is(Tree.Kind.NULL_LITERAL) ? b.rightOperand() : b.leftOperand();
      Constant c = constant(other);
      if (c != null && "null".equals(c.value())) {
        report(cond, b.is(Tree.Kind.EQUAL_TO) != negated, c.why());
      }
      return;
    }
    Constant c = constant(e);
    if (c != null && (c.value().equals("I:0") || c.value().equals("I:1")) && e.symbolType().is("boolean")) {
      report(cond, c.value().equals("I:1") != negated, c.why());
    }
  }

  private record Constant(String value, String why) {
  }

  /** {@code '\\u0000' <= c}, {@code c >= 0}, {@code c < 0}, {@code c <= '\\uffff'} on a char: constant. */
  private static Boolean charRange(BinaryExpressionTree b) {
    if (!b.is(Tree.Kind.LESS_THAN, Tree.Kind.LESS_THAN_OR_EQUAL_TO, Tree.Kind.GREATER_THAN, Tree.Kind.GREATER_THAN_OR_EQUAL_TO)) {
      return null;
    }
    boolean charLeft = Trees.skip(b.leftOperand()).symbolType().is("char") && !b.leftOperand().asConstant().isPresent();
    boolean charRight = Trees.skip(b.rightOperand()).symbolType().is("char") && !b.rightOperand().asConstant().isPresent();
    Integer k = constantInt(charLeft ? b.rightOperand() : charRight ? b.leftOperand() : null);
    if (k == null || charLeft == charRight) {
      return null;
    }
    // normalise to "c OP k"
    Tree.Kind op = b.kind();
    if (charRight) {
      op = switch (op) {
        case LESS_THAN -> Tree.Kind.GREATER_THAN;
        case LESS_THAN_OR_EQUAL_TO -> Tree.Kind.GREATER_THAN_OR_EQUAL_TO;
        case GREATER_THAN -> Tree.Kind.LESS_THAN;
        default -> Tree.Kind.LESS_THAN_OR_EQUAL_TO;
      };
    }
    return switch (op) {
      case GREATER_THAN_OR_EQUAL_TO -> k <= 0 ? Boolean.TRUE : k > 0xFFFF ? Boolean.FALSE : null;
      case GREATER_THAN -> k < 0 ? Boolean.TRUE : k >= 0xFFFF ? Boolean.FALSE : null;
      case LESS_THAN -> k <= 0 ? Boolean.FALSE : k > 0xFFFF ? Boolean.TRUE : null;
      default -> k < 0 ? Boolean.FALSE : k >= 0xFFFF ? Boolean.TRUE : null;
    };
  }

  private static Integer constantInt(ExpressionTree e) {
    if (e == null) {
      return null;
    }
    Object v = e.asConstant().orElse(null);
    return v instanceof Character ch ? Integer.valueOf(ch) : v instanceof Integer i ? i : null;
  }

  private void report(ExpressionTree cond, boolean always, String why) {
    reportIssue(cond, "This condition is always " + always + ": " + why + ".");
  }

  private Constant constant(ExpressionTree e) {
    e = Trees.skip(e);
    Summaries s = summaries.get();
    if (e instanceof MethodInvocationTree m && !m.methodSymbol().isUnknown()) {
      Symbol.MethodSymbol sym = m.methodSymbol();
      boolean fixed = sym.isStatic() || sym.isPrivate() || sym.isFinal() || sym.owner().isFinal();
      String key = Trees.callKey(sym);
      List<Summaries.Method> targets = key == null ? List.of() : s.targets(key);
      if (!fixed || targets.size() != 1) {
        return null;
      }
      String v = targets.get(0).facts().nulls().constantReturn();
      return v == null ? null : new Constant(v, "\"" + targets.get(0).display() + "\" always returns " + show(v));
    }
    Symbol f = Trees.symbol(e);
    if (Trees.isField(f) && f.isPrivate() && !f.isFinal() && f.isStatic() && f.metadata().annotations().isEmpty()) {
      String owner = f.owner().type().erasure().fullyQualifiedName().replace('.', '/');
      String v = s.fieldConstant(owner + "#" + f.name());
      return v == null ? null : new Constant(v, "\"" + f.name() + "\" is only ever set to " + show(v));
    }
    return null;
  }

  private static String show(String v) {
    return switch (v) {
      case "I:0" -> "false";
      case "I:1" -> "true";
      case "null" -> "null";
      default -> v.substring(2);
    };
  }
}
