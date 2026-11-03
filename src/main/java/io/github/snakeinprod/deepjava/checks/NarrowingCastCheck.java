package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.ParenthesizedTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.TypeCastTree;

/** {@code (int) System.currentTimeMillis()} and other longs that are routinely larger than an int. */
@Rule(key = NarrowingCastCheck.KEY)
public class NarrowingCastCheck extends DeepCheck {

  public static final String KEY = "NarrowingCastOverflow";

  public NarrowingCastCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.TYPE_CAST);
  }

  @Override
  public void visitNode(Tree tree) {
    TypeCastTree cast = (TypeCastTree) tree;
    if (!cast.symbolType().isPrimitive() || cast.symbolType().is("long") || cast.symbolType().is("double") || cast.symbolType().is("float")
      || cast.symbolType().is("boolean")) {
      return;
    }
    ExpressionTree e = cast.expression();
    while (e instanceof ParenthesizedTree p) {
      e = p.expression();
    }
    if (e instanceof MethodInvocationTree m && isLarge(m)) {
      reportIssue(cast, "\"" + m.methodSymbol().owner().name() + "." + m.methodSymbol().name() + "()\" is usually larger than "
        + cast.symbolType().name() + " can hold; this cast wraps around.");
    }
  }

  private static boolean isLarge(MethodInvocationTree m) {
    return Trees.isCall(m, "java.lang.System", "currentTimeMillis", "nanoTime")
      || Trees.isCall(m, "java.io.File", "length", "lastModified")
      || Trees.isCall(m, "java.nio.file.Files", "size")
      || Trees.isCall(m, "java.time.Instant", "toEpochMilli", "getEpochSecond")
      || Trees.isCall(m, "java.util.Date", "getTime")
      || Trees.isCall(m, "java.time.Duration", "toMillis", "toNanos")
      || Trees.isCall(m, "java.time.Clock", "millis");
  }
}
