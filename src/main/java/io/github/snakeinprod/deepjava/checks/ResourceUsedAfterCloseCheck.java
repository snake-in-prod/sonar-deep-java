package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.StatementTree;

/** A resource used after it was closed, also when a helper method closed it. */
@Rule(key = ResourceUsedAfterCloseCheck.KEY)
public class ResourceUsedAfterCloseCheck extends ResourceCloseTracker {

  public static final String KEY = "ResourceUsedAfterClose";

  public ResourceUsedAfterCloseCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  void onClosedAgain(Close first, Close second, StatementTree where) {
    // ResourceClosedTwice
  }

  @Override
  void onUseAfterClose(Close close, IdentifierTree use) {
    reportIssue(use, "\"" + use.name() + "\" is used after it was closed by " + close.how() + " at line " + close.line() + ".");
  }
}
