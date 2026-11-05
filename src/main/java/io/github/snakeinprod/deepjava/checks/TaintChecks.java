package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.JavaCheck;

/** Taint rules (section 3) and secret leaks: findings of {@code TaintSolver}, one class per rule. */
public final class TaintChecks {

  private TaintChecks() {
  }

  public static JavaCheck create(String key, Supplier<Summaries> s) {
    return switch (key) {
      case "TaintedSql" -> new TaintedSql(s);
      case "TaintedCommand" -> new TaintedCommand(s);
      case "TaintedPath" -> new TaintedPath(s);
      case "TaintedUrl" -> new TaintedUrl(s);
      case "TaintedLdap" -> new TaintedLdap(s);
      case "TaintedXPath" -> new TaintedXPath(s);
      case "TaintedLog" -> new TaintedLog(s);
      case "TaintedHeader" -> new TaintedHeader(s);
      case "TaintedRedirect" -> new TaintedRedirect(s);
      case "TaintedExpression" -> new TaintedExpression(s);
      case "TaintedFormatString" -> new TaintedFormatString(s);
      case "TaintedSize" -> new TaintedSize(s);
      case "TaintedLoopBound" -> new TaintedLoopBound(s);
      case "TaintedSecurityDecision" -> new TaintedSecurityDecision(s);
      case "SecretLeak" -> new SecretLeak(s);
      default -> throw new IllegalArgumentException(key);
    };
  }

  /** Parity mode only: the loop bounds that {@code TaintedSize} reports otherwise. */
  @Rule(key = "TaintedLoopBound")
  public static final class TaintedLoopBound extends FindingCheck {
    public TaintedLoopBound(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "TaintedSql")
  public static final class TaintedSql extends FindingCheck {
    public TaintedSql(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "TaintedCommand")
  public static final class TaintedCommand extends FindingCheck {
    public TaintedCommand(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "TaintedPath")
  public static final class TaintedPath extends FindingCheck {
    public TaintedPath(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "TaintedUrl")
  public static final class TaintedUrl extends FindingCheck {
    public TaintedUrl(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "TaintedLdap")
  public static final class TaintedLdap extends FindingCheck {
    public TaintedLdap(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "TaintedXPath")
  public static final class TaintedXPath extends FindingCheck {
    public TaintedXPath(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "TaintedLog")
  public static final class TaintedLog extends FindingCheck {
    public TaintedLog(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "TaintedHeader")
  public static final class TaintedHeader extends FindingCheck {
    public TaintedHeader(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "TaintedRedirect")
  public static final class TaintedRedirect extends FindingCheck {
    public TaintedRedirect(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "TaintedExpression")
  public static final class TaintedExpression extends FindingCheck {
    public TaintedExpression(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "TaintedFormatString")
  public static final class TaintedFormatString extends FindingCheck {
    public TaintedFormatString(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "TaintedSize")
  public static final class TaintedSize extends FindingCheck {
    public TaintedSize(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "TaintedSecurityDecision")
  public static final class TaintedSecurityDecision extends FindingCheck {
    public TaintedSecurityDecision(Supplier<Summaries> s) {
      super(s);
    }
  }

  @Rule(key = "SecretLeak")
  public static final class SecretLeak extends FindingCheck {
    public SecretLeak(Supplier<Summaries> s) {
      super(s);
    }
  }
}
