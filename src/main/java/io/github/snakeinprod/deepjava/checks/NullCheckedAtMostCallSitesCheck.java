package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.Optional;
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
    Tree parent = NullReturnDereferenceCheck.skipParentheses(mit);
    if (NullReturnDereferenceCheck.isDereferenceOf(parent, mit)) {
      site = mit;
    } else if (parent instanceof VariableTree v && Trees.isLocal(v.symbol())) {
      IdentifierTree use = NullReturnDereferenceCheck.firstUseIfDereference(v.symbol());
      site = use;
    }
    if (site == null) {
      return;
    }
    Summaries.CallStats stats = s.callStats(key);
    double ratio = isPlainGetter(s, key) ? Math.max(GETTER_RATIO, s.thresholds().minRatio()) : s.thresholds().minRatio();
    if (stats.sites() < s.thresholds().minSites() || stats.checked() < ratio * stats.sites() || stats.checked() >= stats.sites()) {
      return;
    }
    // NullReturnDereference already reports the calls whose null it can explain
    Optional<Summaries.NullReason> reason = s.nullReturn(key);
    if (reason.isPresent() && s.isReportable(key, reason.get()) || NullReturnDereferenceCheck.isNullCheckedElsewhere(mit)) {
      return;
    }
    Summaries.Call c = Summaries.parse(key);
    reportIssue(site, "The result of \"" + Summaries.display(c.owner(), c.name()) + "\" is null-checked at " + stats.checked() + " of "
      + stats.sites() + " call sites, but dereferenced here without a check.");
  }

  /** {@code getX()}/{@code isX()} that returns a field as is. */
  private static boolean isPlainGetter(Summaries s, String key) {
    List<Summaries.Method> targets = s.targets(key);
    return !targets.isEmpty() && targets.stream().allMatch(t -> t.name().matches("(get|is)[A-Z].*")
      && !t.facts().nulls().returnedFields().isEmpty());
  }
}
