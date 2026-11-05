package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;

/** {@code token.equals(expected)}: secrets compared with an early-exit comparison leak their content through timing. */
@Rule(key = TimingUnsafeSecretComparisonCheck.KEY)
public class TimingUnsafeSecretComparisonCheck extends DeepCheck {

  public static final String KEY = "TimingUnsafeSecretComparison";

  private static final String SECRET = "(?i).*(password|passwd|secret|token|apikey|api_key|hmac|signature|otp|digest).*";

  public TimingUnsafeSecretComparisonCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.METHOD_INVOCATION);
  }

  @Override
  public void visitNode(Tree tree) {
    MethodInvocationTree mit = (MethodInvocationTree) tree;
    ExpressionTree a;
    ExpressionTree b;
    boolean unresolvedEquals = mit.methodSymbol().isUnknown() && "equals".equals(Trees.name(mit));
    // a lambda parameter of an unresolved library interface (an authenticator callback) has no known type
    if ((Trees.isCall(mit, "java.lang.String", "equals", "contentEquals") || unresolvedEquals) && mit.arguments().size() == 1
      && Trees.receiver(mit) != null) {
      a = Trees.receiver(mit);
      b = mit.arguments().get(0);
    } else if (Trees.isCall(mit, "java.util.Arrays", "equals") && mit.arguments().size() == 2) {
      a = mit.arguments().get(0);
      b = mit.arguments().get(1);
    } else {
      return;
    }
    Tree method = Trees.enclosingMethod(mit);
    if (method instanceof org.sonar.plugins.java.api.tree.MethodTree mt && mt.simpleName().name().matches("equals|hashCode|compareTo")) {
      // value equality of a data class, not an authentication check
      return;
    }
    // parity: a constant field (a hard-coded expected password) is compared like any value
    boolean parity = summaries.get().options().referenceParity();
    if (isLiteral(a, parity) || isLiteral(b, parity) || !(isSecret(a) || isSecret(b))) {
      return;
    }
    reportIssue(mit, "Secrets compared with \"" + Trees.name(mit) + "()\" leak their content through timing; use MessageDigest.isEqual().");
  }

  private static boolean isLiteral(ExpressionTree e, boolean parity) {
    e = Trees.skip(e);
    return (parity ? e instanceof org.sonar.plugins.java.api.tree.LiteralTree : e.asConstant().isPresent()) || e.is(Tree.Kind.NULL_LITERAL);
  }

  private static boolean isSecret(ExpressionTree e) {
    e = Trees.skip(e);
    // new String(password), String.valueOf(password)
    if (e instanceof org.sonar.plugins.java.api.tree.NewClassTree nc && nc.arguments().size() == 1) {
      e = Trees.skip(nc.arguments().get(0));
    } else if (e instanceof MethodInvocationTree m && "valueOf".equals(Trees.name(m)) && m.arguments().size() == 1) {
      e = Trees.skip(m.arguments().get(0));
    }
    String name = e instanceof IdentifierTree id ? id.name()
      : e instanceof MemberSelectExpressionTree ms ? ms.identifier().name()
      : e instanceof MethodInvocationTree m ? Trees.name(m) : "";
    return name.matches(SECRET) && !name.matches("(?i).*(name|type|header|length|uri|url|path|endpoint|id)$");
  }
}
