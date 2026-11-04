package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;

/** {@code Pattern.compile(regex, flags)} where a flag has no effect on that regex or conflicts with another. */
@Rule(key = RegexFlagsCheck.KEY)
public class RegexFlagsCheck extends DeepCheck {

  public static final String KEY = "RegexFlagsUseless";

  public RegexFlagsCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.METHOD_INVOCATION);
  }

  @Override
  public void visitNode(Tree tree) {
    guarded(tree, () -> check((MethodInvocationTree) tree));
  }

  private void check(MethodInvocationTree mit) {
    if (!Trees.isCall(mit, "java.util.regex.Pattern", "compile") || mit.arguments().size() != 2) {
      return;
    }
    String regex = mit.arguments().get(0).asConstant(String.class).orElse(null);
    Set<String> flags = new HashSet<>();
    if (regex == null || !flags(mit.arguments().get(1), flags)) {
      return;
    }
    String plain = regex.replaceAll("\\\\.", "").replaceAll("\\[[^\\]]*\\]", "x");
    String problem = null;
    if (flags.contains("LITERAL") && flags.stream().anyMatch(f -> !Set.of("LITERAL", "CASE_INSENSITIVE", "UNICODE_CASE").contains(f))) {
      problem = "LITERAL turns off every flag except CASE_INSENSITIVE and UNICODE_CASE";
    } else if (flags.contains("UNICODE_CASE") && !flags.contains("CASE_INSENSITIVE")) {
      problem = "UNICODE_CASE does nothing without CASE_INSENSITIVE";
    } else if (flags.contains("MULTILINE") && !plain.contains("^") && !plain.contains("$")) {
      problem = "MULTILINE has no effect: the regex has no ^ or $";
    } else if (flags.contains("DOTALL") && !plain.contains(".")) {
      problem = "DOTALL has no effect: the regex has no \".\"";
    } else if (flags.contains("CASE_INSENSITIVE") && !regex.matches("(?s).*\\p{L}.*")) {
      problem = "CASE_INSENSITIVE has no effect: the regex has no letters";
    }
    if (problem != null) {
      reportIssue(mit.arguments().get(1), problem + ".");
    }
  }

  /** Flags combined with {@code |}; false if anything else is in the expression. */
  private static boolean flags(ExpressionTree e, Set<String> out) {
    e = Trees.skip(e);
    if (e instanceof BinaryExpressionTree b && b.is(Tree.Kind.OR, Tree.Kind.PLUS)) {
      return flags(b.leftOperand(), out) && flags(b.rightOperand(), out);
    }
    if (e instanceof MemberSelectExpressionTree ms && ms.expression().symbolType().is("java.util.regex.Pattern")) {
      out.add(ms.identifier().name());
      return true;
    }
    if (e instanceof org.sonar.plugins.java.api.tree.IdentifierTree id && id.symbol().owner() != null
      && id.symbol().owner().type().is("java.util.regex.Pattern")) {
      out.add(id.name());
      return true;
    }
    return false;
  }
}
