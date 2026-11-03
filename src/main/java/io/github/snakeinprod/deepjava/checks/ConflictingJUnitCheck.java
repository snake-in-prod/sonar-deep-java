package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.sonar.check.Rule;
import org.sonar.plugins.java.api.tree.AnnotationTree;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.Tree;

/** Test annotations that do not go together, and classes that mix JUnit 4 and JUnit 5 tests. */
@Rule(key = ConflictingJUnitCheck.KEY)
public class ConflictingJUnitCheck extends DeepCheck {

  public static final String KEY = "ConflictingJUnitAnnotations";

  private static final Set<String> JUPITER_TESTS = Set.of("org.junit.jupiter.api.Test", "org.junit.jupiter.params.ParameterizedTest",
    "org.junit.jupiter.api.RepeatedTest", "org.junit.jupiter.api.TestFactory", "org.junit.jupiter.api.TestTemplate");
  private static final Set<String> LIFECYCLE = Set.of("org.junit.jupiter.api.BeforeEach", "org.junit.jupiter.api.AfterEach",
    "org.junit.jupiter.api.BeforeAll", "org.junit.jupiter.api.AfterAll", "org.junit.Before", "org.junit.After", "org.junit.BeforeClass",
    "org.junit.AfterClass");
  private static final String JUNIT4_TEST = "org.junit.Test";

  public ConflictingJUnitCheck(Supplier<Summaries> summaries) {
    super(summaries);
  }

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return List.of(Tree.Kind.CLASS);
  }

  @Override
  public void visitNode(Tree tree) {
    ClassTree c = (ClassTree) tree;
    boolean junit4 = false;
    boolean junit5 = false;
    for (Tree member : c.members()) {
      if (!(member instanceof MethodTree m)) {
        continue;
      }
      Set<String> names = new HashSet<>();
      for (AnnotationTree a : m.modifiers().annotations()) {
        names.add(a.symbolType().fullyQualifiedName());
      }
      long tests = names.stream().filter(JUPITER_TESTS::contains).count() + (names.contains(JUNIT4_TEST) ? 1 : 0);
      boolean lifecycle = names.stream().anyMatch(LIFECYCLE::contains);
      if (tests > 1) {
        reportIssue(m.simpleName(), "This method has " + tests + " test annotations; keep one, or the test runs twice or fails to start.");
      } else if (tests == 1 && lifecycle) {
        reportIssue(m.simpleName(), "A method cannot be both a test and a lifecycle method.");
      }
      junit4 |= names.contains(JUNIT4_TEST);
      junit5 |= names.stream().anyMatch(JUPITER_TESTS::contains);
    }
    if (junit4 && junit5 && c.simpleName() != null) {
      reportIssue(c.simpleName(), "This class mixes JUnit 4 and JUnit 5 tests; only one set runs, depending on the engine.");
    }
  }
}
