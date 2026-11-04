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
          && !params.get(i).matches("arg\\d+")) {
          reportIssue(mit.arguments().get(i), "\"" + ai + "\" and \"" + aj + "\" are passed to the parameters named \"" + params.get(i)
            + "\" and \"" + params.get(j) + "\": are they swapped?");
          return;
        }
      }
    }
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
