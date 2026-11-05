package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.List;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.IssuableSubscriptionVisitor;
import org.sonar.plugins.java.api.tree.CompilationUnitTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.Tree;

/** Reports, on each file, the findings that the pre-pass computed from bytecode for this rule. */
public abstract class FindingCheck extends IssuableSubscriptionVisitor {

  private final Supplier<Summaries> summaries;

  protected FindingCheck(Supplier<Summaries> summaries) {
    this.summaries = summaries;
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.COMPILATION_UNIT);
  }

  @Override
  public void visitNode(Tree tree) {
    String path = path((CompilationUnitTree) tree, context);
    String key = getClass().getAnnotation(Rule.class).key();
    for (Summaries.Finding f : summaries.get().findings(key, path)) {
      if (accept(f)) {
        addIssue(f.line(), f.message());
      }
    }
  }

  /** The source path findings use: package directories and file name ({@code com/acme/Foo.java}). */
  static String path(CompilationUnitTree cu, org.sonar.plugins.java.api.JavaFileScannerContext context) {
    String pkg = cu.packageDeclaration() == null ? "" : name(cu.packageDeclaration().packageName()).replace('.', '/') + "/";
    return pkg + context.getInputFile().filename();
  }

  /** Whether to report a finding on this file (subclasses filter by source text). */
  protected boolean accept(Summaries.Finding f) {
    return true;
  }

  static String name(ExpressionTree e) {
    if (e instanceof MemberSelectExpressionTree ms) {
      return name(ms.expression()) + "." + ms.identifier().name();
    }
    return e instanceof IdentifierTree id ? id.name() : "";
  }
}
