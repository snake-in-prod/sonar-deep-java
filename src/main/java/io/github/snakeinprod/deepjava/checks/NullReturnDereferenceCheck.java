package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.BytecodeScanner;
import io.github.snakeinprod.deepjava.prepass.Summaries;
import io.github.snakeinprod.deepjava.prepass.Summaries.NullReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BaseTreeVisitor;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;

/**
 * The result of a method that can return null (project method, transitively, or library model) is dereferenced
 * without a check: directly ({@code m().x()}) or through a local whose first use is a dereference.
 */
@Rule(key = NullReturnDereferenceCheck.KEY)
public class NullReturnDereferenceCheck extends IssuableSubscriptionVisitor {

  public static final String KEY = "NullReturnDereference";

  private static final Set<String> NULLABLE_ANNOTATIONS = Set.of("Nullable", "CheckForNull", "NullableDecl");

  private final Supplier<Summaries> summaries;

  public NullReturnDereferenceCheck(Supplier<Summaries> summaries) {
    this.summaries = summaries;
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.METHOD_INVOCATION);
  }

  @Override
  public void visitNode(Tree tree) {
    MethodInvocationTree mit = (MethodInvocationTree) tree;
    Symbol.MethodSymbol sym = mit.methodSymbol();
    if (sym.isUnknown() || isAnnotatedNullable(sym)) {
      // annotated methods are sonar-java's S2259 territory
      return;
    }
    String key = callKey(sym);
    if (key == null) {
      return;
    }
    Optional<NullReason> reason = summaries.get().nullReturn(key);
    if (reason.isEmpty() || isGuarded(mit, reason.get().guards())) {
      return;
    }
    NullReason r = reason.get();
    String message = "\"" + r.method() + "\" can return null (" + r.detail() + "); check the result before dereferencing it.";

    Tree parent = skipParentheses(mit);
    if (isDereferenceOf(parent, mit)) {
      reportIssue(mit, message, declarationLocation(sym), null);
    } else if (parent instanceof VariableTree v && isLocal(v.symbol())) {
      IdentifierTree use = firstUseIfDereference(v.symbol());
      if (use != null) {
        List<JavaFileScannerContext.Location> secondaries = new ArrayList<>(declarationLocation(sym));
        secondaries.add(new JavaFileScannerContext.Location("Value assigned here", mit));
        reportIssue(use, message, secondaries, null);
      }
    }
  }

  /** Same format as {@link BytecodeScanner#callKey}. */
  static String callKey(Symbol.MethodSymbol sym) {
    String signature = sym.signature();
    int paren = signature == null ? -1 : signature.indexOf('(');
    if (paren < 0 || "<init>".equals(sym.name()) || sym.owner() == null) {
      return null;
    }
    String owner = sym.owner().type().erasure().fullyQualifiedName().replace('.', '/');
    return BytecodeScanner.callKey(sym.isStatic() || sym.isPrivate(), owner, sym.name(), signature.substring(paren));
  }

  private static boolean isAnnotatedNullable(Symbol sym) {
    return sym.metadata().annotations().stream()
      .anyMatch(a -> NULLABLE_ANNOTATIONS.contains(a.symbol().name()));
  }

  /** Parent of {@code tree}, looking through parentheses and casts. */
  private static Tree skipParentheses(Tree tree) {
    Tree p = tree.parent();
    while (p != null && (p.is(Tree.Kind.PARENTHESIZED_EXPRESSION) || p.is(Tree.Kind.TYPE_CAST))) {
      p = p.parent();
    }
    return p;
  }

  private static boolean isDereferenceOf(Tree parent, ExpressionTree value) {
    if (parent == null) {
      return false;
    }
    return switch (parent.kind()) {
      case MEMBER_SELECT -> contains(((MemberSelectExpressionTree) parent).expression(), value);
      case ARRAY_ACCESS_EXPRESSION -> contains(((org.sonar.plugins.java.api.tree.ArrayAccessExpressionTree) parent).expression(), value);
      case FOR_EACH_STATEMENT -> contains(((org.sonar.plugins.java.api.tree.ForEachStatement) parent).expression(), value);
      case SYNCHRONIZED_STATEMENT -> contains(((org.sonar.plugins.java.api.tree.SynchronizedStatementTree) parent).expression(), value);
      default -> false;
    };
  }

  /** {@code outer} is {@code inner}, possibly wrapped in parentheses and casts. */
  private static boolean contains(ExpressionTree outer, ExpressionTree inner) {
    for (Tree t = inner; t != null; t = t.parent()) {
      if (t == outer) {
        return true;
      }
      if (t != inner && !t.is(Tree.Kind.PARENTHESIZED_EXPRESSION, Tree.Kind.TYPE_CAST)) {
        return false;
      }
    }
    return false;
  }

  private static boolean isLocal(Symbol var) {
    return var.isVariableSymbol() && var.owner() != null && var.owner().isMethodSymbol();
  }

  /** The first use of a never-reassigned local, if that use dereferences it. */
  private static IdentifierTree firstUseIfDereference(Symbol var) {
    List<IdentifierTree> usages = new ArrayList<>(var.usages());
    if (usages.isEmpty()) {
      return null;
    }
    for (IdentifierTree u : usages) {
      if (u.parent() instanceof AssignmentExpressionTree a && a.variable() == u) {
        return null;
      }
    }
    usages.sort((a, b) -> {
      var ta = a.identifierToken();
      var tb = b.identifierToken();
      return ta.range().start().compareTo(tb.range().start());
    });
    IdentifierTree first = usages.get(0);
    return isDereferenceOf(skipParentheses(first), first) ? first : null;
  }

  /** A guard method (e.g. {@code containsKey}) is called on the same receiver variable in the same method. */
  private static boolean isGuarded(MethodInvocationTree mit, Set<String> guards) {
    if (guards.isEmpty() || !(mit.methodSelect() instanceof MemberSelectExpressionTree select)) {
      return false;
    }
    Symbol receiver = receiverSymbol(select.expression());
    if (receiver == null) {
      return false;
    }
    Tree method = mit;
    while (method != null && !method.is(Tree.Kind.METHOD, Tree.Kind.CONSTRUCTOR, Tree.Kind.LAMBDA_EXPRESSION)) {
      method = method.parent();
    }
    if (method == null) {
      return false;
    }
    boolean[] found = {false};
    method.accept(new BaseTreeVisitor() {
      @Override
      public void visitMethodInvocation(MethodInvocationTree call) {
        if (call.methodSelect() instanceof MemberSelectExpressionTree s
          && guards.contains(s.identifier().name())
          && receiver.equals(receiverSymbol(s.expression()))) {
          found[0] = true;
        }
        super.visitMethodInvocation(call);
      }
    });
    return found[0];
  }

  private static Symbol receiverSymbol(ExpressionTree e) {
    if (e instanceof IdentifierTree id) {
      return id.symbol().isUnknown() ? null : id.symbol();
    }
    if (e instanceof MemberSelectExpressionTree ms && ms.expression().is(Tree.Kind.IDENTIFIER)
      && "this".equals(((IdentifierTree) ms.expression()).name())) {
      return ms.identifier().symbol().isUnknown() ? null : ms.identifier().symbol();
    }
    return null;
  }

  private static List<JavaFileScannerContext.Location> declarationLocation(Symbol.MethodSymbol sym) {
    MethodTree decl = sym.declaration();
    return decl == null ? List.of() : List.of(new JavaFileScannerContext.Location("Can return null", decl.simpleName()));
  }
}
