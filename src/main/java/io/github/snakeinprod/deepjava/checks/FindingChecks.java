package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;

/** Rules computed from the project's bytecode (see {@code ProjectAnalyses}); one class per rule. */
public final class FindingChecks {

  private FindingChecks() {
  }

  @Rule(key = IgnoredResult.KEY)
  public static final class IgnoredResult extends FindingCheck {
    public static final String KEY = "IgnoredResultStatistical";

    public IgnoredResult(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = MissingSuperCall.KEY)
  public static final class MissingSuperCall extends FindingCheck {
    public static final String KEY = "MissingSuperCall";

    public MissingSuperCall(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = InconsistentLocking.KEY)
  public static final class InconsistentLocking extends FindingCheck {
    public static final String KEY = "InconsistentLocking";

    public InconsistentLocking(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = GuardedBy.KEY)
  public static final class GuardedBy extends FindingCheck {
    public static final String KEY = "GuardedByViolation";

    public GuardedBy(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = MixedLocks.KEY)
  public static final class MixedLocks extends FindingCheck {
    public static final String KEY = "MixedLocks";

    public MixedLocks(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = LockNotReleased.KEY)
  public static final class LockNotReleased extends FindingCheck {
    public static final String KEY = "LockNotReleasedAcrossMethods";

    public LockNotReleased(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = NonReentrantReacquired.KEY)
  public static final class NonReentrantReacquired extends FindingCheck {
    public static final String KEY = "NonReentrantLockReacquired";

    public NonReentrantReacquired(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = StaticFieldInstanceLock.KEY)
  public static final class StaticFieldInstanceLock extends FindingCheck {
    public static final String KEY = "StaticFieldInstanceLock";

    public StaticFieldInstanceLock(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = LockOrder.KEY)
  public static final class LockOrder extends FindingCheck {
    public static final String KEY = "LockOrderCycle";

    public LockOrder(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = UncheckedInEntryPoint.KEY)
  public static final class UncheckedInEntryPoint extends FindingCheck {
    public static final String KEY = "UncheckedExceptionInEntryPoint";

    public UncheckedInEntryPoint(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = UnreachableCatch.KEY)
  public static final class UnreachableCatch extends FindingCheck {
    public static final String KEY = "UnreachableCatch";

    public UnreachableCatch(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = CodeAfterThrow.KEY)
  public static final class CodeAfterThrow extends FindingCheck {
    public static final String KEY = "CodeAfterAlwaysThrows";

    public CodeAfterThrow(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = DeadEnumCase.KEY)
  public static final class DeadEnumCase extends FindingCheck {
    public static final String KEY = "DeadEnumCase";

    public DeadEnumCase(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = EmptyMapLookup.KEY)
  public static final class EmptyMapLookup extends FindingCheck {
    public static final String KEY = "EmptyMapLookup";

    public EmptyMapLookup(Supplier<Summaries> s) {
      super(s);
    }
  }

  /** Parity mode only: unreachable null branches in Lombok-generated members, reported on the annotation line. */
  @Rule(key = LombokUnreachable.KEY)
  public static final class LombokUnreachable extends FindingCheck {
    public static final String KEY = "LombokUnreachableCode";
    private static final java.util.regex.Pattern LOMBOK = java.util.regex.Pattern.compile("@(lombok\\.)?(Data|Value|NonNull|Builder)\\b");

    public LombokUnreachable(Supplier<Summaries> s) {
      super(s);
    }

    @Override
    protected boolean accept(Summaries.Finding f) {
      List<String> lines = context.getFileLines();
      return f.line() <= lines.size() && LOMBOK.matcher(lines.get(f.line() - 1)).find();
    }
  }
}
