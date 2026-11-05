package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.NewClassTree;
import org.sonar.plugins.java.api.tree.Tree;

/**
 * Reference parity mode only: a stream created inline as the argument of a wrapper whose constructor can throw
 * ({@code new GZIPInputStream(new FileInputStream(f))}): when the constructor throws, nothing closes the inner stream,
 * try-with-resources included (it only closes the wrapper).
 */
@Rule(key = ResourceLeakCheck.KEY)
public class ResourceLeakCheck extends DeepCheck {

  public static final String KEY = "ResourceLeak";

  /** Wrappers whose constructor reads or writes the stream (a header) and can throw. */
  private static final Set<String> THROWING_WRAPPERS = Set.of("java.util.zip.GZIPInputStream", "java.util.zip.GZIPOutputStream",
    "java.io.ObjectInputStream", "java.io.ObjectOutputStream");

  public ResourceLeakCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.NEW_CLASS, Tree.Kind.VARIABLE);
  }

  @Override
  public void visitNode(Tree tree) {
    if (tree instanceof org.sonar.plugins.java.api.tree.VariableTree v) {
      local(v);
      return;
    }
    NewClassTree wrapper = (NewClassTree) tree;
    if (!summaries.get().options().referenceParity() || !THROWING_WRAPPERS.contains(wrapper.symbolType().fullyQualifiedName())) {
      return;
    }
    for (ExpressionTree a : wrapper.arguments()) {
      if (Trees.skip(a) instanceof NewClassTree inner && inner.symbolType().isSubtypeOf("java.io.Closeable")) {
        reportIssue(inner, "\"" + inner.symbolType().name() + "\" is not closed if the \"" + wrapper.symbolType().name()
          + "\" constructor throws; open it in its own try-with-resources.");
      }
    }
  }

  /** {@code GZIPInputStream g = new GZIPInputStream(in);} in a local that is never closed, returned or passed on. */
  private void local(org.sonar.plugins.java.api.tree.VariableTree v) {
    if (!summaries.get().options().referenceParity() || !(v.initializer() != null && Trees.skip(v.initializer()) instanceof NewClassTree nc)
      || !THROWING_WRAPPERS.contains(nc.symbolType().fullyQualifiedName()) || !Trees.isLocal(v.symbol())
      || v.parent() != null && v.parent().is(Tree.Kind.LIST) || v.parent() instanceof org.sonar.plugins.java.api.tree.TryStatementTree) {
      return;
    }
    for (org.sonar.plugins.java.api.tree.IdentifierTree u : v.symbol().usages()) {
      Tree p = u.parent();
      boolean closed = p instanceof org.sonar.plugins.java.api.tree.MemberSelectExpressionTree ms && "close".equals(ms.identifier().name());
      if (closed || p != null && (p.is(Tree.Kind.ARGUMENTS, Tree.Kind.RETURN_STATEMENT, Tree.Kind.ASSIGNMENT, Tree.Kind.VARIABLE, Tree.Kind.LAMBDA_EXPRESSION))) {
        return;
      }
    }
    reportIssue(nc, "\"" + v.simpleName().name() + "\" is never closed.");
  }
}
