package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;

/**
 * Callers check a method's result for null at most call sites (default 80% of at least 5), and this one dereferences
 * it without a check. The evidence is the project's own habit, not an analysis of the callee.
 */
@Rule(key = NullCheckedAtMostCallSitesCheck.KEY)
public class NullCheckedAtMostCallSitesCheck extends DeepCheck {

  public static final String KEY = "NullCheckedAtMostCallSites";

  /** Plain getters are checked out of habit more often: they need a stronger majority. */
  private static final double GETTER_RATIO = 0.9;
  private static final double PARITY_RATIO = 0.5;
  private static final int PARITY_MIN_SITES = 3;

  public NullCheckedAtMostCallSitesCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.METHOD_INVOCATION);
  }

  @Override
  public void visitNode(Tree tree) {
    MethodInvocationTree mit = (MethodInvocationTree) tree;
    Summaries s = summaries.get();
    String key = Trees.resolveCallKey(mit, s);
    if (key == null) {
      return;
    }
    Tree site = null;
    boolean parity = s.options().referenceParity();
    Tree parent = NullReturnDereferenceCheck.skipParentheses(mit);
    // a Lombok getter has no type in the syntax tree: its descriptor says whether it returns a wrapper (unboxing)
    boolean boxed = Derefs.isBoxed(mit.symbolType()) || key.matches(".*\\)Ljava/lang/(Long|Integer|Short|Byte|Boolean|Double|Float|Character);");
    // parity: requireNonNull(f(x)) and f(x) passed to a parameter the callee dereferences count as dereferences
    boolean argument = parity && !Derefs.isDereference(parent, mit, boxed) && !NullReturnDereferenceCheck.isRequireNonNullArgument(parent, mit)
      && NullArgumentCheck.passedToDereferencingParameter(s, mit, parent);
    if (argument || Derefs.isDereference(parent, mit, boxed) || parity && NullReturnDereferenceCheck.isRequireNonNullArgument(parent, mit)) {
      site = Trees.methodName(mit);
    } else if (parent instanceof VariableTree v && Trees.isLocal(v.symbol())) {
      site = NullReturnDereferenceCheck.firstUseIfDereference(v.symbol(), parity);
    }
    if (site == null) {
      return;
    }
    // parity: the reference analyzer reports at lower ratios; getters get no stricter cut-off
    double ratio = parity ? PARITY_RATIO : isPlainGetter(s, key) ? Math.max(GETTER_RATIO, s.thresholds().minRatio()) : s.thresholds().minRatio();
    int minSites = parity ? Math.min(PARITY_MIN_SITES, s.thresholds().minSites()) : s.thresholds().minSites();
    // main code alone first (what the reference counts), then with the test callers parity mode adds, but only when
    // main code has enough call sites of its own: test-dominated statistics (assertNotNull) make most extra issues
    Summaries.CallStats main = s.callStats(key, false);
    Summaries.CallStats stats = main;
    String scope = parity ? " in main code" : "";
    if (!qualifies(stats, ratio, minSites)) {
      stats = s.callStats(key, true);
      scope = " counting tests";
      if (main.sites() < minSites || !qualifies(stats, ratio, minSites)) {
        return;
      }
    }
    // NullReturnDereference already reports the calls whose null it can explain
    // (an argument it does not report: that is NullPassedToDereferencedParameter's, a different reference class)
    if (!argument && NullReturnDereferenceCheck.reportableReason(mit, s, key).isPresent() || NullReturnDereferenceCheck.isNullCheckedElsewhere(mit)) {
      return;
    }
    Summaries.Call c = Summaries.parse(key);
    reportIssue(site, "The result of \"" + Summaries.display(c.owner(), c.name()) + "\" is null-checked at " + stats.checked() + " of "
      + stats.sites() + " call sites" + scope + ", but dereferenced here without a check.");
  }

  private static boolean qualifies(Summaries.CallStats stats, double ratio, int minSites) {
    return stats.sites() >= minSites && stats.checked() >= ratio * stats.sites() && stats.checked() < stats.sites();
  }

  /** {@code getX()}/{@code isX()} that returns a field as is. */
  private static boolean isPlainGetter(Summaries s, String key) {
    List<Summaries.Method> targets = s.targets(key);
    return !targets.isEmpty() && targets.stream().allMatch(t -> t.name().matches("(get|is)[A-Z].*")
      && !t.facts().nulls().returnedFields().isEmpty());
  }
}
