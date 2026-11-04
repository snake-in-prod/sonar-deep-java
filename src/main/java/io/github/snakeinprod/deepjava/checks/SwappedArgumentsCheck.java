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
    return List.of(Tree.Kind.METHOD_INVOCATION);
  }

  @Override
  public void visitNode(Tree tree) {
    MethodInvocationTree mit = (MethodInvocationTree) tree;
    List<String> params = paramNames(mit);
    if (params.size() != mit.arguments().size() || params.size() < 2) {
      return;
    }
    List<String> args = new ArrayList<>();
    for (var a : mit.arguments()) {
      args.add(a instanceof IdentifierTree id ? id.name() : null);
    }
    for (int i = 0; i < args.size(); i++) {
      for (int j = i + 1; j < args.size(); j++) {
        String ai = args.get(i);
        String aj = args.get(j);
        if (ai != null && aj != null && !ai.equals(aj) && ai.equals(params.get(j)) && aj.equals(params.get(i))
          && !params.get(i).matches("arg\\d+") && !bothOrders(mit, ai, aj)) {
          reportIssue(mit.arguments().get(i), "\"" + ai + "\" and \"" + aj + "\" are passed to the parameters named \"" + params.get(i)
            + "\" and \"" + params.get(j) + "\": are they swapped?");
          return;
        }
      }
    }
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
