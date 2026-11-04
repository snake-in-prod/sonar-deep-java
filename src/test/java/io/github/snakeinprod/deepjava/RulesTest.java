package io.github.snakeinprod.deepjava;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.snakeinprod.deepjava.checks.NullReturnDereferenceCheck;
import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.sonar.java.checks.verifier.CheckVerifier;
import org.sonar.plugins.java.api.JavaFileScanner;

/** One test file per rule: {@code src/test/files/rules/<key>.java} with {@code // Noncompliant} markers. */
class RulesTest {

  @TempDir
  Path classes;

  static Stream<Rules.Spec> rules() {
    return Rules.ALL.stream().filter(r -> !r.key().equals(NullReturnDereferenceCheck.KEY));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rules")
  void rule(Rules.Spec spec) throws Exception {
    String file = "src/test/files/rules/" + spec.key() + ".java";
    Summaries summaries = TestSummaries.compile(Summaries.Thresholds.DEFAULT, TestSummaries.optionsFor(file), classes, file);
    CheckVerifier.newVerifier()
      .onFile(file)
      .withClassPath(classpath())
      .withCheck((JavaFileScanner) spec.check().apply(() -> summaries))
      .verifyIssues();
  }

  /** Compiled test classes plus the JUnit jars of this JVM (for the test-code rules). */
  private List<java.io.File> classpath() {
    List<java.io.File> cp = new java.util.ArrayList<>(List.of(classes.toFile()));
    for (String e : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
      if (e.contains("junit") || e.contains("opentest4j")) {
        cp.add(new java.io.File(e));
      }
    }
    return cp;
  }

  @Test
  void every_rule_has_metadata_and_a_test_file() {
    assertThat(Rules.ALL).extracting(Rules.Spec::key).doesNotHaveDuplicates();
    for (Rules.Spec spec : rules().toList()) {
      assertThat(Path.of("src/test/files/rules/" + spec.key() + ".java")).exists();
      assertThat(spec.html()).contains("<p>");
    }
  }

  @Test
  void rule_keys_match_check_annotations() throws Exception {
    for (Rules.Spec spec : Rules.ALL) {
      Object check = spec.check().apply(() -> null);
      assertThat(check.getClass().getAnnotation(org.sonar.check.Rule.class).key()).isEqualTo(spec.key());
    }
  }

  static {
    try {
      Files.createDirectories(Path.of("target"));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
