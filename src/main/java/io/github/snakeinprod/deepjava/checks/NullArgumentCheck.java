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
    return List.of(Tree.Kind.METHOD_INVOCATION, Tree.Kind.NEW_CLASS);
  }

  @Override
  public void visitNode(Tree tree) {
    Summaries s = summaries.get();
    List<? extends ExpressionTree> args;
    Symbol.MethodSymbol sym;
    String key;
    if (tree instanceof org.sonar.plugins.java.api.tree.NewClassTree nc) {
      args = nc.arguments();
      sym = nc.methodSymbol();
      key = sym.isUnknown() ? null : Trees.callKey(sym);
    } else {
      MethodInvocationTree mit = (MethodInvocationTree) tree;
      args = mit.arguments();
      sym = mit.methodSymbol();
      key = Trees.resolveCallKey(mit, s);
    }
    if (key == null || !sym.isUnknown() && sym.isVarArgsMethod()) {
      return;
    }
    List<Summaries.Method> targets = s.targets(key);
    if (targets.isEmpty()) {
      library(s, key, args);
      return;
    }
    if (targets.size() > s.thresholds().maxTargets()) {
      return;
    }
    boolean parity = s.options().referenceParity();
    for (int i = 0; i < args.size(); i++) {
      int index = i;
      boolean everyPath = targets.stream().allMatch(t -> t.facts().uses().derefParams().contains(index));
      boolean somePath = !everyPath && targets.stream().allMatch(t -> s.maybeDereferences(t, index));
      ExpressionTree arg = args.get(i);
      boolean literal = Trees.skip(arg).is(Tree.Kind.NULL_LITERAL) || nullLocal(arg);
      // on some path only: a null literal (or a local holding one); in parity mode any nullable value
      if (!everyPath && !(somePath && (literal || parity))) {
        continue;
      }
      String why = nullWhy(s, arg);
      if (why != null) {
        List<String> names = targets.get(0).facts().info().paramNames();
        String param = i < names.size() && !names.get(i).isEmpty() ? "\"" + names.get(i) + "\"" : "#" + (i + 1);
        reportIssue(arg, "\"" + targets.get(0).display() + "\" dereferences its parameter " + param
          + (everyPath ? " on every path" : " without a null check") + ", but " + why + " is passed here.");
      }
    }
  }

  /**
   * {@code value} is an argument that the called method dereferences (on some path, never comparing it to null), or a
   * library method modelled as dereferencing it.
   */
  static boolean passedToDereferencingParameter(Summaries s, ExpressionTree value, Tree parent) {
    if (parent == null || !parent.is(Tree.Kind.ARGUMENTS)) {
      return false;
    }
    String key;
    List<? extends ExpressionTree> args;
    if (parent.parent() instanceof MethodInvocationTree call) {
      key = Trees.resolveCallKey(call, s);
      args = call.arguments();
    } else if (parent.parent() instanceof org.sonar.plugins.java.api.tree.NewClassTree nc && !nc.methodSymbol().isUnknown()) {
      key = Trees.callKey(nc.methodSymbol());
      args = nc.arguments();
    } else {
      return false;
    }
    int index = args.indexOf(value);
    if (key == null || index < 0) {
      return false;
    }
    List<Summaries.Method> targets = s.targets(key);
    if (targets.isEmpty()) {
      Summaries.Call c = Summaries.parse(key);
      return s.models().dereferencesParameter(c.owner(), c.name(), c.desc(), index, s.hierarchy());
    }
    return targets.size() <= s.thresholds().maxTargets() && targets.stream().allMatch(t -> s.maybeDereferences(t, index));
  }

  /** Library methods modelled as dereferencing a parameter ({@code new StringReader(x)}, {@code DocumentBuilder.parse(x)}). */
  private void library(Summaries s, String key, List<? extends ExpressionTree> args) {
    Summaries.Call c = Summaries.parse(key);
    for (int i = 0; i < args.size(); i++) {
      if (!s.models().dereferencesParameter(c.owner(), c.name(), c.desc(), i, s.hierarchy())) {
        continue;
      }
      String why = nullWhy(s, args.get(i));
      if (why != null && !Trees.skip(args.get(i)).is(Tree.Kind.NULL_LITERAL)) {
        reportIssue(args.get(i), "\"" + Summaries.display(c.owner(), c.name()).replace("<init>()", "new()") + "\" dereferences this argument, but "
          + why + " is passed here.");
      }
    }
  }

  /** A local declared as {@code null} and never assigned again. */
  private static boolean nullLocal(ExpressionTree arg) {
    Symbol v = Trees.symbol(arg);
    return Trees.isLocal(v) && v.declaration() instanceof org.sonar.plugins.java.api.tree.VariableTree d && d.initializer() != null
      && Trees.skip(d.initializer()).is(Tree.Kind.NULL_LITERAL) && notReassigned(v);
  }

  private static boolean notReassigned(Symbol v) {
    return v.usages().stream().noneMatch(u -> u.parent() instanceof org.sonar.plugins.java.api.tree.AssignmentExpressionTree a && a.variable() == u);
  }

  private static String nullWhy(Summaries s, ExpressionTree arg) {
    ExpressionTree a = Trees.skip(arg);
    if (a.is(Tree.Kind.NULL_LITERAL)) {
      return "null";
    }
    Symbol local = Trees.symbol(a);
    if (Trees.isLocal(local) && local.declaration() instanceof org.sonar.plugins.java.api.tree.VariableTree d && d.initializer() != null
      && notReassigned(local) && !nullTested(local)) {
      // a local holding null or a nullable result
      if (Trees.skip(d.initializer()).is(Tree.Kind.NULL_LITERAL)) {
        return "\"" + local.name() + "\", which is null,";
      }
      a = Trees.skip(d.initializer());
    }
    // parity: a method result passed on is not a reference class (declared nullables are NullableDereference's)
    if (a instanceof MethodInvocationTree call && !s.options().referenceParity()) {
      String key = Trees.resolveCallKey(call, s);
      Optional<Summaries.NullReason> r = key == null ? Optional.empty() : s.nullReturn(key);
      if (r.isPresent() && r.get().guards().isEmpty() && s.isReportable(key, r.get())) {
        return "the result of \"" + r.get().method() + "\", which can be null (" + r.get().detail() + "),";
      }
    }
    return null;
  }

  private static boolean nullTested(Symbol local) {
    return local.usages().stream().anyMatch(u -> Derefs.isNullTest(Derefs.parent(u), u));
  }
}
