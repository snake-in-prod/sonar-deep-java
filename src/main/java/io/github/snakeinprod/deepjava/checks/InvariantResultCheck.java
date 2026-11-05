package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.function.Supplier;
import org.sonar.check.Rule;

/**
 * Reference parity mode only: a comparison or condition whose value does not depend on its inputs (a char compared
 * with {@code '\u0000'} or {@code '￿'}, a literal condition). Outside parity mode {@link ConstantConditionCheck}
 * reports these.
 */
@Rule(key = InvariantResultCheck.KEY)
public class InvariantResultCheck extends ConstantConditionCheck {

  public static final String KEY = "InvariantResult";

  public InvariantResultCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  protected boolean reports(boolean invariant) {
    return invariant && summaries.get().options().referenceParity();
  }
}
