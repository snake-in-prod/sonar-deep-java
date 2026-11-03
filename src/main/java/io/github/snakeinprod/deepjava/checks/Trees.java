package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.BytecodeScanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BaseTreeVisitor;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.ConditionalExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.LambdaExpressionTree;
import org.sonar.plugins.java.api.tree.LiteralTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.ParenthesizedTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.TypeCastTree;
import org.sonar.plugins.java.api.tree.UnaryExpressionTree;

/** Small syntax-tree helpers shared by the checks. */
final class Trees {

  private Trees() {
  }

  /** Same format as {@link BytecodeScanner#callKey}, or null. */
  static String callKey(Symbol.MethodSymbol sym) {
    String signature = sym.signature();
    int paren = signature == null ? -1 : signature.indexOf('(');
    if (paren < 0 || sym.owner() == null) {
      return null;
    }
    String owner = sym.owner().type().erasure().fullyQualifiedName().replace('.', '/');
    return BytecodeScanner.callKey(sym.isStatic() || sym.isPrivate() || "<init>".equals(sym.name()), owner, sym.name(), signature.substring(paren));
  }

  static ExpressionTree skip(ExpressionTree e) {
    while (true) {
      if (e instanceof ParenthesizedTree p) {
        e = p.expression();
      } else if (e instanceof TypeCastTree c) {
        e = c.expression();
      } else {
        return e;
      }
    }
  }

  static int line(Tree t) {
    return t.firstToken().range().start().line();
  }

  /** The variable an expression names ({@code x} or {@code this.x}), or null. */
  static Symbol symbol(ExpressionTree e) {
    e = skip(e);
    if (e instanceof IdentifierTree id) {
      return id.symbol().isUnknown() ? null : id.symbol();
    }
    if (e instanceof MemberSelectExpressionTree ms && ms.expression() instanceof IdentifierTree q && "this".equals(q.name())) {
      return ms.identifier().symbol().isUnknown() ? null : ms.identifier().symbol();
    }
    return null;
  }

  static boolean isLocal(Symbol var) {
    return var != null && var.isVariableSymbol() && var.owner() != null && var.owner().isMethodSymbol();
  }

  static boolean isField(Symbol var) {
    return var != null && var.isVariableSymbol() && var.owner() != null && var.owner().isTypeSymbol();
  }

  /** Receiver of a call {@code r.m()}, or null. */
  static ExpressionTree receiver(MethodInvocationTree mit) {
    return mit.methodSelect() instanceof MemberSelectExpressionTree ms ? ms.expression() : null;
  }

  static String name(MethodInvocationTree mit) {
    return mit.methodSymbol().isUnknown() ? (mit.methodSelect() instanceof MemberSelectExpressionTree ms
      ? ms.identifier().name() : mit.methodSelect() instanceof IdentifierTree id ? id.name() : "") : mit.methodSymbol().name();
  }

  static boolean isCall(MethodInvocationTree mit, String ownerFqn, String... names) {
    Symbol.MethodSymbol s = mit.methodSymbol();
    if (s.isUnknown() || !s.owner().type().is(ownerFqn) && !s.owner().type().isSubtypeOf(ownerFqn)) {
      return false;
    }
    for (String n : names) {
      if (n.equals(s.name())) {
        return true;
      }
    }
    return false;
  }

  /** Structural equality of simple expressions: names, field accesses, literals and calls. */
  static boolean same(ExpressionTree a, ExpressionTree b) {
    a = skip(a);
    b = skip(b);
    if (a instanceof IdentifierTree x && b instanceof IdentifierTree y) {
      return x.name().equals(y.name()) && x.symbol().equals(y.symbol());
    }
    if (a instanceof MemberSelectExpressionTree x && b instanceof MemberSelectExpressionTree y) {
      return x.identifier().name().equals(y.identifier().name()) && same(x.expression(), y.expression());
    }
    if (a instanceof LiteralTree x && b instanceof LiteralTree y) {
      return x.kind() == y.kind() && x.value().equals(y.value());
    }
    if (a instanceof MethodInvocationTree x && b instanceof MethodInvocationTree y) {
      if (!Objects.equals(x.methodSymbol().signature(), y.methodSymbol().signature()) || x.arguments().size() != y.arguments().size()
        || !same(x.methodSelect(), y.methodSelect())) {
        return false;
      }
      for (int i = 0; i < x.arguments().size(); i++) {
        if (!same(x.arguments().get(i), y.arguments().get(i))) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  static boolean isConstant(ExpressionTree e) {
    if (e.asConstant().isPresent()) {
      return true;
    }
    Symbol s = e instanceof IdentifierTree id ? id.symbol() : e instanceof MemberSelectExpressionTree ms ? ms.identifier().symbol() : null;
    return s != null && s.isVariableSymbol() && s.isStatic() && s.isFinal();
  }

  static Integer intConstant(ExpressionTree e) {
    return e.asConstant(Integer.class).orElse(null);
  }

  /** All trees of a kind below {@code root}, not entering lambdas or nested classes. */
  static <T extends Tree> List<T> collect(Tree root, Class<T> type) {
    List<T> out = new ArrayList<>();
    root.accept(new BaseTreeVisitor() {
      @Override
      protected void scan(Tree tree) {
        if (type.isInstance(tree)) {
          out.add(type.cast(tree));
        }
        super.scan(tree);
      }

      @Override
      public void visitLambdaExpression(LambdaExpressionTree t) {
        // not executed in place
      }

      @Override
      public void visitClass(ClassTree t) {
        // not executed in place
      }
    });
    if (type.isInstance(root) && !out.contains(root)) {
      out.add(0, type.cast(root));
    }
    return out;
  }

  /** Whether {@code t} lies on a branch of {@code stop} that may not run (conditional, &&, ||, lambda). */
  static boolean isConditional(Tree t, Tree stop) {
    for (Tree child = t, p = t.parent(); p != null && child != stop; child = p, p = p.parent()) {
      if (p instanceof ConditionalExpressionTree c && child != c.condition()) {
        return true;
      }
      if (p instanceof BinaryExpressionTree b && p.is(Tree.Kind.CONDITIONAL_AND, Tree.Kind.CONDITIONAL_OR) && child == b.rightOperand()) {
        return true;
      }
      if (p instanceof LambdaExpressionTree || p instanceof ClassTree) {
        return true;
      }
      if (p instanceof StatementTree && p != stop) {
        // a nested statement (if/loop body...) of the top-level statement
        return true;
      }
    }
    return false;
  }

  /** {@code x = ...}, {@code x++}, {@code x += ...} anywhere below {@code root}. */
  static boolean assigns(Tree root, Symbol var) {
    return collect(root, AssignmentExpressionTree.class).stream().anyMatch(a -> var.equals(symbol(a.variable())))
      || collect(root, UnaryExpressionTree.class).stream()
        .anyMatch(u -> u.is(Tree.Kind.POSTFIX_INCREMENT, Tree.Kind.POSTFIX_DECREMENT, Tree.Kind.PREFIX_INCREMENT, Tree.Kind.PREFIX_DECREMENT)
          && var.equals(symbol(u.expression())));
  }

  static List<StatementTree> statementsAfter(BlockTree block, StatementTree s) {
    List<StatementTree> body = block.body();
    int i = body.indexOf(s);
    return i < 0 ? List.of() : body.subList(i + 1, body.size());
  }

  static <T extends Tree> boolean any(Tree root, Class<T> type, Predicate<T> p) {
    return collect(root, type).stream().anyMatch(p);
  }

  /** Enclosing method, constructor or lambda. */
  static Tree enclosingMethod(Tree t) {
    Tree m = t;
    while (m != null && !m.is(Tree.Kind.METHOD, Tree.Kind.CONSTRUCTOR, Tree.Kind.LAMBDA_EXPRESSION)) {
      m = m.parent();
    }
    return m;
  }
}
