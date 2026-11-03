package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.function.Supplier;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;

/** A syntax-tree rule with access to the project summaries. */
public abstract class DeepCheck extends IssuableSubscriptionVisitor {

  protected final Supplier<Summaries> summaries;

  protected DeepCheck(Supplier<Summaries> summaries) {
    this.summaries = summaries;
  }
}
