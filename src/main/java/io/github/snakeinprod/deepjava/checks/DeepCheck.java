package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.function.Supplier;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;

/** A syntax-tree rule with access to the project summaries. */
public abstract class DeepCheck extends IssuableSubscriptionVisitor {

  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DeepCheck.class);

  protected final Supplier<Summaries> summaries;

  protected DeepCheck(Supplier<Summaries> summaries) {
    this.summaries = summaries;
  }

  /**
   * Runs a step that works on source text (regexes, token lists): an error there skips this node of this file and is
   * logged, it never stops the analysis.
   */
  protected void guarded(org.sonar.plugins.java.api.tree.Tree tree, Runnable step) {
    try {
      step.run();
    } catch (StackOverflowError | RuntimeException e) {
      org.sonar.check.Rule rule = getClass().getAnnotation(org.sonar.check.Rule.class);
      LOG.warn("Deep Java: rule {} skipped a part of {} (line {}): {}", rule == null ? getClass().getSimpleName() : rule.key(),
        context == null || context.getInputFile() == null ? "?" : context.getInputFile(), tree.firstToken() == null ? "?"
          : tree.firstToken().range().start().line(), e.toString());
    }
  }
}
