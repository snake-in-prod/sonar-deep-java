package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.StatementTree;

/** A resource closed again after it was closed, also by a helper method. */
@Rule(key = ResourceClosedTwiceCheck.KEY)
public class ResourceClosedTwiceCheck extends ResourceCloseTracker {

  public static final String KEY = "ResourceClosedTwice";

  public ResourceClosedTwiceCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  void onClosedAgain(Close first, Close second, StatementTree where) {
    reportIssue(where, "\"" + first.var().name() + "\" was already closed by " + first.how() + " at line " + first.line() + ".");
  }

  @Override
  void onUseAfterClose(Close close, IdentifierTree use) {
    // ResourceUsedAfterClose
  }
}
