package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;

/** {@code f(b, a)} for {@code f(int a, int b)}: two arguments named like the parameters but in swapped order. */
@Rule(key = SwappedArgumentsCheck.KEY)
public class SwappedArgumentsCheck extends DeepCheck {

  public static final String KEY = "SwappedArguments";

  public SwappedArgumentsCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.METHOD_INVOCATION, Tree.Kind.NEW_CLASS);
  }

  @Override
  public void visitNode(Tree tree) {
    if (tree instanceof org.sonar.plugins.java.api.tree.NewClassTree nc) {
      // new Relation(target, source) for Relation(source, target)
      check(nc.arguments(), constructorParams(nc), null);
      return;
    }
    MethodInvocationTree mit = (MethodInvocationTree) tree;
    check(mit.arguments(), paramNames(mit), mit);
  }

  private List<String> constructorParams(org.sonar.plugins.java.api.tree.NewClassTree nc) {
    Symbol.MethodSymbol sym = nc.methodSymbol();
    if (sym.isUnknown()) {
      // a Lombok constructor (@AllArgsConstructor, @Data): only in the bytecode, its parameters named after the fields
      org.sonar.plugins.java.api.semantic.Type t = nc.symbolType();
      String key = t.isUnknown() ? null : summaries.get().findMethod(t.erasure().fullyQualifiedName().replace('.', '/'), "<init>", nc.arguments().size());
      List<Summaries.Method> targets = key == null ? List.of() : summaries.get().targets(key.replaceFirst("^!?", "!"));
      return targets.isEmpty() ? List.of() : targets.get(0).facts().info().paramNames();
    }
    if (sym.declaration() != null) {
      List<String> names = new ArrayList<>();
      sym.declarationParameters().forEach(p -> names.add(p.name()));
      return names;
    }
    String key = Trees.callKey(sym);
    List<Summaries.Method> targets = key == null ? List.of() : summaries.get().targets(key);
    return targets.isEmpty() ? List.of() : targets.get(0).facts().info().paramNames();
  }

  private void check(List<? extends org.sonar.plugins.java.api.tree.ExpressionTree> arguments, List<String> params, MethodInvocationTree mit) {
    if (params.size() != arguments.size() || params.size() < 2 || arguments.isEmpty() || inSwappingMethod(arguments.get(0))) {
      return;
    }
    List<String> args = new ArrayList<>();
    for (var a : arguments) {
      args.add(a instanceof IdentifierTree id ? id.name() : null);
    }
    for (int i = 0; i < args.size(); i++) {
      for (int j = i + 1; j < args.size(); j++) {
        String ai = args.get(i);
        String aj = args.get(j);
        if (ai != null && aj != null && !ai.equals(aj) && ai.equals(params.get(j)) && aj.equals(params.get(i))
          && !params.get(i).matches("arg\\d+") && (mit == null || !bothOrders(mit, ai, aj))) {
          reportIssue(arguments.get(i), "\"" + ai + "\" and \"" + aj + "\" are passed to the parameters named \"" + params.get(i)
            + "\" and \"" + params.get(j) + "\": are they swapped?");
          return;
        }
      }
    }
  }

  /** {@code invert()} returning {@code new Fraction(denominator, numerator)}: the swap is the point. */
  private static boolean inSwappingMethod(Tree t) {
    Tree m = Trees.enclosingMethod(t);
    return m instanceof org.sonar.plugins.java.api.tree.MethodTree mt
      && mt.simpleName().name().matches("(?i).*(invert|inverse|revers|swap|flip|transpos|mirror|opposite|negat|reciprocal|backward).*");
  }

  /** {@code f(b, c) || f(c, b)}: both orders in one statement are deliberate symmetry. */
  private static boolean bothOrders(MethodInvocationTree mit, String ai, String aj) {
    Tree method = mit;
    while (method != null && !(method instanceof org.sonar.plugins.java.api.tree.StatementTree)) {
      method = method.parent();
    }
    return method != null && Trees.any(method, MethodInvocationTree.class, m -> m != mit && Trees.name(m).equals(Trees.name(mit))
      && m.arguments().size() == mit.arguments().size() && m.arguments().stream().anyMatch(a -> a instanceof IdentifierTree id && id.name().equals(aj))
      && m.arguments().indexOf(m.arguments().stream().filter(a -> a instanceof IdentifierTree id && id.name().equals(aj)).findFirst().get())
        < m.arguments().indexOf(m.arguments().stream().filter(a -> a instanceof IdentifierTree id && id.name().equals(ai)).findFirst().orElse(m.arguments().get(0))));
  }

  private List<String> paramNames(MethodInvocationTree mit) {
    Symbol.MethodSymbol sym = mit.methodSymbol();
    if (!sym.isUnknown() && sym.declaration() != null) {
      List<String> names = new ArrayList<>();
      sym.declarationParameters().forEach(p -> names.add(p.name()));
      return names;
    }
    String key = Trees.resolveCallKey(mit, summaries.get());
    List<Summaries.Method> targets = key == null ? List.of() : summaries.get().targets(key);
    return targets.isEmpty() ? List.of() : targets.get(0).facts().info().paramNames();
  }
}
