package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;

/** {@code null}, or a value that can be null, passed to a parameter that the callee dereferences on every path. */
@Rule(key = NullArgumentCheck.KEY)
public class NullArgumentCheck extends DeepCheck {

  public static final String KEY = "NullPassedToDereferencedParameter";

  public NullArgumentCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.METHOD_INVOCATION);
  }

  @Override
  public void visitNode(Tree tree) {
    MethodInvocationTree mit = (MethodInvocationTree) tree;
    Symbol.MethodSymbol sym = mit.methodSymbol();
    if (!sym.isUnknown() && sym.isVarArgsMethod()) {
      return;
    }
    Summaries s = summaries.get();
    String key = Trees.resolveCallKey(mit, s);
    if (key == null) {
      return;
    }
    List<Summaries.Method> targets = s.targets(key);
    if (targets.isEmpty() || targets.size() > s.thresholds().maxTargets()) {
      return;
    }
    for (int i = 0; i < mit.arguments().size(); i++) {
      int index = i;
      if (!targets.stream().allMatch(t -> t.facts().uses().derefParams().contains(index))) {
        continue;
      }
      String why = nullWhy(s, mit.arguments().get(i));
      if (why != null) {
        List<String> names = targets.get(0).facts().info().paramNames();
        String param = i < names.size() && !names.get(i).isEmpty() ? "\"" + names.get(i) + "\"" : "#" + (i + 1);
        reportIssue(mit.arguments().get(i), "\"" + targets.get(0).display() + "\" dereferences its parameter " + param
          + " on every path, but " + why + " is passed here.");
      }
    }
  }

  private static String nullWhy(Summaries s, ExpressionTree arg) {
    ExpressionTree a = Trees.skip(arg);
    if (a.is(Tree.Kind.NULL_LITERAL)) {
      return "null";
    }
    if (a instanceof MethodInvocationTree call) {
      String key = Trees.resolveCallKey(call, s);
      Optional<Summaries.NullReason> r = key == null ? Optional.empty() : s.nullReturn(key);
      if (r.isPresent() && r.get().guards().isEmpty() && s.isReportable(key, r.get())) {
        return "the result of \"" + r.get().method() + "\", which can be null (" + r.get().detail() + "),";
      }
    }
    return null;
  }
}
