package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.BytecodeScanner;
import io.github.snakeinprod.deepjava.prepass.Summaries;
import io.github.snakeinprod.deepjava.prepass.Summaries.NullReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BaseTreeVisitor;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.LiteralTree;
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

  private final Supplier<Summaries> summaries;

  public NullReturnDereferenceCheck(Supplier<Summaries> summaries) {
    this.summaries = summaries;
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.METHOD_INVOCATION, Tree.Kind.METHOD);
  }

  @Override
  public void visitNode(Tree tree) {
    if (tree instanceof MethodTree method) {
      visitOverride(method);
      nullableFields(method);
      return;
    }
    MethodInvocationTree mit = (MethodInvocationTree) tree;
    Symbol.MethodSymbol sym = mit.methodSymbol();
    Summaries s = summaries.get();
    String key = Trees.resolveCallKey(mit, s);
    Optional<NullReason> reason = reportableReason(mit, s, key);
    if (reason.isEmpty()) {
      return;
    }
    NullReason r = reason.get();
    Summaries.CallStats stats = s.callStats(key);
    String evidence = !r.isStrong() && stats.checked() > 0 ?"; null-checked at " + stats.checked() + " of " + stats.sites() + " call sites" : "";
    String message = "\"" + r.method() + "\" can return null (" + r.detail() + evidence + "); check the result before dereferencing it.";

    boolean parity = s.options().referenceParity();
    Tree parent = skipParentheses(mit);
    boolean boxed = Derefs.isBoxed(mit.symbolType()) || key.matches(".*\\)Ljava/lang/(Long|Integer|Short|Byte|Boolean|Double|Float|Character);");
    if (Derefs.isDereference(parent, mit, boxed) || parity && isRequireNonNullArgument(parent, mit)) {
      reportIssue(Trees.methodName(mit), message, declarationLocation(sym), null);
    } else if (parent instanceof VariableTree v && isLocal(v.symbol())) {
      IdentifierTree use = firstUseIfDereference(v.symbol(), parity);
      if (use != null) {
        List<JavaFileScannerContext.Location> secondaries = new ArrayList<>(declarationLocation(sym));
        secondaries.add(new JavaFileScannerContext.Location("Value assigned here", mit));
        reportIssue(use, message, secondaries, null);
      }
    }
  }

  /** A field annotated nullable dereferenced in a method of its class before any null test in that method. */
  private void nullableFields(MethodTree method) {
    if (method.block() == null) {
      return;
    }
    Summaries.Nullable nullable = summaries.get().options().nullable();
    java.util.Set<Symbol> seen = new java.util.HashSet<>();
    for (IdentifierTree id : Trees.collect(method.block(), IdentifierTree.class)) {
      Symbol f = id.symbol();
      if (!Trees.isField(f) || !seen.add(f) || f.isStatic() && f.isFinal() || !isNullable(f, nullable)) {
        continue;
      }
      List<IdentifierTree> uses = new ArrayList<>(f.usages().stream().filter(u -> within(u, method)).toList());
      uses.sort((a, b) -> a.identifierToken().range().start().compareTo(b.identifierToken().range().start()));
      for (IdentifierTree u : uses) {
        ExpressionTree value = u.parent() instanceof MemberSelectExpressionTree ms && ms.identifier() == u ? ms : u;
        Tree p = Derefs.parent(value);
        if (p instanceof AssignmentExpressionTree a && a.variable() == value || Derefs.isNullTest(p, value)) {
          break;
        }
        if (Derefs.isDereference(p, value, Derefs.isBoxed(f.type()))) {
          reportIssue(value, "\"" + f.name() + "\" is annotated nullable; check it before dereferencing it.");
          break;
        }
      }
    }
  }

  private static boolean within(Tree t, Tree ancestor) {
    for (Tree p = t; p != null; p = p.parent()) {
      if (p == ancestor) {
        return true;
      }
    }
    return false;
  }

  private static boolean isNullable(Symbol f, Summaries.Nullable nullable) {
    java.util.Map<String, String> names = new java.util.HashMap<>();
    for (var a : f.metadata().annotations()) {
      names.put(a.symbol().name(), "");
      names.put(a.symbol().type().fullyQualifiedName(), "");
    }
    return nullable.matches(names);
  }

  /** Why the call's result can be null, when a dereference of it is reported by this rule. */
  static Optional<NullReason> reportableReason(MethodInvocationTree mit, Summaries s, String key) {
    if (key == null) {
      return Optional.empty();
    }
    Symbol.MethodSymbol sym = mit.methodSymbol();
    Optional<NullReason> reason = s.nullReturn(key);
    if (reason.isEmpty() || isGuarded(mit, reason.get().guards()) || !s.isReportable(key, reason.get())
      || !sym.isUnknown() && isStandardSystemProperty(sym, mit) || isNullCheckedElsewhere(mit)
      || reason.get().kind() == NullReason.Kind.NULL_FOR_NULL_ARGUMENT && !s.options().referenceParity() && isNonNullArgument(mit)) {
      return Optional.empty();
    }
    return reason;
  }

  /** A mapper called with {@code new X()}, a literal or {@code this}: it does not take its null path. */
  private static boolean isNonNullArgument(MethodInvocationTree mit) {
    return mit.arguments().stream().map(Trees::skip).allMatch(a -> a.is(Tree.Kind.NEW_CLASS, Tree.Kind.NEW_ARRAY, Tree.Kind.STRING_LITERAL)
      || a instanceof IdentifierTree id && "this".equals(id.name()));
  }

  /** {@code Objects.requireNonNull(value)}: the reference analyzer counts it as a dereference. */
  static boolean isRequireNonNullArgument(Tree parent, ExpressionTree value) {
    return parent != null && parent.is(Tree.Kind.ARGUMENTS) && parent.parent() instanceof MethodInvocationTree call
      && "requireNonNull".equals(Trees.name(call)) && !call.arguments().isEmpty() && contains(call.arguments().get(0), value);
  }

  /**
   * A project method overrides a library method whose parameter is annotated nullable, and dereferences that
   * parameter before any check ({@code postHandle(..., @Nullable ModelAndView mav)}).
   */
  private void visitOverride(MethodTree method) {
    Symbol.MethodSymbol sym = method.symbol();
    if (sym.isUnknown() || method.block() == null) {
      return;
    }
    Summaries s = summaries.get();
    for (Symbol.MethodSymbol overridden : sym.overriddenSymbols()) {
      String key = Trees.callKey(overridden);
      if (overridden.isUnknown() || key == null) {
        continue;
      }
      Summaries.Call c = Summaries.parse(key);
      for (int i = 0; i < method.parameters().size(); i++) {
        String lib = s.libraryNullableParameter(c.owner(), c.nameDesc(), i);
        if (lib == null) {
          continue;
        }
        VariableTree param = method.parameters().get(i);
        IdentifierTree use = firstUseIfDereference(param.symbol(), s.options().referenceParity());
        if (use != null) {
          reportIssue(use, "Parameter \"" + param.simpleName().name() + "\" is annotated nullable in \"" + lib
            + "\", which this method overrides; check it before dereferencing it.");
        }
      }
      return;
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

  /** Parent of {@code tree}, looking through parentheses and casts. */
  static Tree skipParentheses(Tree tree) {
    Tree p = tree.parent();
    while (p != null && (p.is(Tree.Kind.PARENTHESIZED_EXPRESSION) || p.is(Tree.Kind.TYPE_CAST))) {
      p = p.parent();
    }
    return p;
  }

  static boolean isDereferenceOf(Tree parent, ExpressionTree value) {
    return Derefs.isDereference(parent, value, Derefs.isBoxed(value.symbolType()));
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

  static IdentifierTree firstUseIfDereference(Symbol var) {
    return firstUseIfDereference(var, false);
  }

  /** The first dereference of a never-reassigned local before any null test (see {@link Derefs#firstDereference}). */
  static IdentifierTree firstUseIfDereference(Symbol var, boolean parity) {
    return Derefs.firstDereference(var, parity);
  }

  /**
   * For lookup-like library methods (models with guards): the key is computed, which usually means an invariant
   * the author knows, or a guard method (e.g. {@code containsKey}) is called on the same receiver in the same method.
   */
  static boolean isGuarded(MethodInvocationTree mit, Set<String> guards) {
    if (guards.isEmpty()) {
      return false;
    }
    if (!mit.arguments().isEmpty() && !isConstant(mit.arguments().get(0))) {
      return true;
    }
    if (!(mit.methodSelect() instanceof MemberSelectExpressionTree select)) {
      return false;
    }
    Symbol receiver = receiverSymbol(select.expression());
    if (receiver == null) {
      return false;
    }
    if (receiver.isStatic() && receiver.isFinal() || isFilledAtDeclaration(receiver)) {
      // lookup in a constant table, or a local created with its content: content is known to the author
      return true;
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

  /** Properties every JVM defines, so {@code System.getProperty("os.name")} is never null. */
  private static final Set<String> STANDARD_PROPERTIES = Set.of("java.version", "java.vendor", "java.vendor.url", "java.home",
    "java.class.version", "java.class.path", "java.io.tmpdir", "java.specification.version", "java.vm.name", "java.vm.vendor",
    "java.vm.version", "java.runtime.version", "os.name", "os.arch", "os.version", "file.separator", "path.separator",
    "line.separator", "user.name", "user.home", "user.dir", "native.encoding", "file.encoding");

  private static boolean isStandardSystemProperty(Symbol.MethodSymbol sym, MethodInvocationTree mit) {
    return "getProperty".equals(sym.name()) && sym.owner().type().is("java.lang.System") && !mit.arguments().isEmpty()
      && mit.arguments().get(0).asConstant(String.class).map(STANDARD_PROPERTIES::contains).orElse(false);
  }

  /** The same call is compared with null in the same method: {@code key() == null ? 0 : key().limit()}. */
  static boolean isNullCheckedElsewhere(MethodInvocationTree mit) {
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
      public void visitBinaryExpression(BinaryExpressionTree b) {
        if (b.is(Tree.Kind.EQUAL_TO, Tree.Kind.NOT_EQUAL_TO)) {
          ExpressionTree other = b.leftOperand().is(Tree.Kind.NULL_LITERAL) ? b.rightOperand()
            : b.rightOperand().is(Tree.Kind.NULL_LITERAL) ? b.leftOperand() : null;
          if (other != null && other != mit && same(other, mit)) {
            found[0] = true;
          }
        }
        super.visitBinaryExpression(b);
      }
    });
    return found[0];
  }

  /** Structural equality of simple expressions: names, field accesses, literals and calls. */
  private static boolean same(ExpressionTree a, ExpressionTree b) {
    a = unparenthesize(a);
    b = unparenthesize(b);
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

  /**
   * A local initialised with its content: {@code new EnumMap<>(Map.of(...))}, {@code Map.of(...)},
   * {@code stream.collect(partitioningBy(...))}.
   */
  private static boolean isFilledAtDeclaration(Symbol var) {
    if (!isLocal(var) || !(var.declaration() instanceof VariableTree decl) || decl.initializer() == null) {
      return false;
    }
    ExpressionTree init = unparenthesize(decl.initializer());
    if (init instanceof org.sonar.plugins.java.api.tree.NewClassTree nc) {
      return nc.arguments().stream().anyMatch(a -> !a.symbolType().isPrimitive());
    }
    if (init instanceof MethodInvocationTree call) {
      String name = call.methodSymbol().name();
      return (Set.of("of", "ofEntries", "copyOf").contains(name) && !call.arguments().isEmpty())
        // partitioningBy always holds both keys, true and false
        || call.arguments().stream().anyMatch(a -> a instanceof MethodInvocationTree m && "partitioningBy".equals(m.methodSymbol().name()));
    }
    return false;
  }

  private static ExpressionTree unparenthesize(ExpressionTree e) {
    while (e instanceof org.sonar.plugins.java.api.tree.ParenthesizedTree p) {
      e = p.expression();
    }
    return e;
  }

  /** A literal, or a static final field / enum constant. */
  private static boolean isConstant(ExpressionTree e) {
    if (e.asConstant().isPresent()) {
      return true;
    }
    Symbol s = e instanceof IdentifierTree id ? id.symbol()
      : e instanceof MemberSelectExpressionTree ms ? ms.identifier().symbol() : null;
    return s != null && s.isVariableSymbol() && s.isStatic() && s.isFinal();
  }

  private static Symbol receiverSymbol(ExpressionTree e) {
    if (e instanceof IdentifierTree id) {
      return id.symbol().isUnknown() ? null : id.symbol();
    }
    if (e instanceof MemberSelectExpressionTree ms && !ms.identifier().symbol().isUnknown()
      && (ms.identifier().symbol().isStatic()
        || ms.expression().is(Tree.Kind.IDENTIFIER) && "this".equals(((IdentifierTree) ms.expression()).name()))) {
      return ms.identifier().symbol();
    }
    return null;
  }

  private static List<JavaFileScannerContext.Location> declarationLocation(Symbol.MethodSymbol sym) {
    MethodTree decl = sym.isUnknown() ? null : sym.declaration();
    return decl == null ? List.of() : List.of(new JavaFileScannerContext.Location("Can return null", decl.simpleName()));
  }
}
