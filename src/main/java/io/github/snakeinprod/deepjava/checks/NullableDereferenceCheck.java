package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.function.Supplier;
import org.sonar.check.Rule;

/**
 * Reference parity mode only: a value declared nullable (annotation on the method, its return type, a field or a
 * parameter, in project, library or generated code) dereferenced without a check. Outside parity mode
 * {@link NullReturnDereferenceCheck} reports these.
 */
@Rule(key = NullableDereferenceCheck.KEY)
public class NullableDereferenceCheck extends NullReturnDereferenceCheck {

  public static final String KEY = "NullableDereference";

  private final Supplier<Summaries> summaries;

  public NullableDereferenceCheck(Supplier<Summaries> summaries) {
    super(summaries);
    this.summaries = summaries;
  }

  @Override
  protected boolean reports(boolean declaredNullable) {
    return declaredNullable && summaries.get().options().referenceParity();
  }
}
