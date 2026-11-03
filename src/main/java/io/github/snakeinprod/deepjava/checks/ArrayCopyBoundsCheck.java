package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.NewArrayTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;

/** {@code System.arraycopy} / {@code Arrays.copyOfRange} with constant bounds that do not fit. */
@Rule(key = ArrayCopyBoundsCheck.KEY)
public class ArrayCopyBoundsCheck extends DeepCheck {

  public static final String KEY = "ArrayCopyOutOfBounds";

  public ArrayCopyBoundsCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.METHOD_INVOCATION);
  }

  @Override
  public void visitNode(Tree tree) {
    MethodInvocationTree mit = (MethodInvocationTree) tree;
    List<ExpressionTree> a = mit.arguments();
    if (Trees.isCall(mit, "java.lang.System", "arraycopy") && a.size() == 5) {
      Integer srcPos = Trees.intConstant(a.get(1));
      Integer dstPos = Trees.intConstant(a.get(3));
      Integer len = Trees.intConstant(a.get(4));
      if (srcPos == null || dstPos == null || len == null) {
        return;
      }
      Integer srcLen = length(a.get(0));
      Integer dstLen = length(a.get(2));
      if (srcPos < 0 || dstPos < 0 || len < 0) {
        reportIssue(mit, "Negative position or length: this always throws IndexOutOfBoundsException.");
      } else if (srcLen != null && srcPos + len > srcLen) {
        reportIssue(a.get(4), "Copying " + len + " elements from position " + srcPos + " reads past the end of the " + srcLen + "-element source.");
      } else if (dstLen != null && dstPos + len > dstLen) {
        reportIssue(a.get(4), "Copying " + len + " elements to position " + dstPos + " writes past the end of the " + dstLen + "-element destination.");
      }
    } else if (Trees.isCall(mit, "java.util.Arrays", "copyOfRange") && a.size() >= 3) {
      Integer from = Trees.intConstant(a.get(1));
      Integer to = Trees.intConstant(a.get(2));
      Integer len = length(a.get(0));
      if (from != null && to != null && from > to) {
        reportIssue(mit, "\"from\" (" + from + ") is greater than \"to\" (" + to + "): this always throws IllegalArgumentException.");
      } else if (from != null && len != null && from > len) {
        reportIssue(a.get(1), "\"from\" (" + from + ") is past the end of the " + len + "-element array.");
      }
    }
  }

  /** Length of a local array created with a constant size and never reassigned. */
  private static Integer length(ExpressionTree e) {
    Symbol s = Trees.symbol(e);
    if (!Trees.isLocal(s) || !(s.declaration() instanceof VariableTree v) || !(v.initializer() instanceof NewArrayTree na)) {
      return null;
    }
    if (s.usages().stream().anyMatch(u -> u.parent() instanceof org.sonar.plugins.java.api.tree.AssignmentExpressionTree as && as.variable() == u)) {
      return null;
    }
    if (na.dimensions().size() == 1 && na.dimensions().get(0).expression() != null) {
      return Trees.intConstant(na.dimensions().get(0).expression());
    }
    return na.dimensions().isEmpty() || na.dimensions().get(0).expression() == null ? na.initializers().size() : null;
  }
}
