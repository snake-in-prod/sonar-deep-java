package io.github.snakeinprod.deepjava;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.sonar.java.checks.verifier.CheckVerifier;
import org.sonar.plugins.java.api.JavaFileScanner;

/**
 * Every rule, with and without parity mode, on a file with a 10,000-character string literal full of escapes and a
 * 5,000-character line without literals, on the default thread stack: no rule may fail on it.
 */
class StressTest {

  @TempDir
  Path dir;

  static Stream<Arguments> rules() {
    return Rules.ALL.stream().flatMap(r -> Stream.of(Arguments.of(r, false), Arguments.of(r, true)));
  }

  static String source(boolean parity) {
    StringBuilder xml = new StringBuilder();
    while (xml.length() < 10_000) {
      xml.append("<item name=\\\"a\\\" value=\\\"b|c\\\">\\n");
    }
    StringBuilder sum = new StringBuilder("1");
    while (sum.length() < 5_000) {
      sum.append(" + 1");
    }
    return "package stress;" + (parity ? " // parity" : "") + "\n\n"
      + "class Big {\n"
      + "  static final String XML = \"" + xml + "\";\n\n"
      + "  int sum() {\n    return " + sum + ";\n  }\n\n"
      + "  void pairs(java.util.Map<String, String> m) {\n"
      + "    m.put(\"first\", XML + \"" + xml + "\");\n"
      + "    m.put(\"second\", XML + \"" + xml + "\");\n"
      + "  }\n\n"
      + "  String strip(String s, boolean b) {\n"
      + "    if (b) {\n      return s.replace(\"" + xml + "\", \"\");\n    } else {\n      return s.replace(\"" + xml + "x\", \"\");\n    }\n"
      + "  }\n"
      + "}\n";
  }

  @ParameterizedTest(name = "{0} parity={1}")
  @MethodSource("rules")
  void long_literals_and_lines_never_fail_a_rule(Rules.Spec spec, boolean parity) throws Exception {
    Path file = dir.resolve("Big.java");
    Files.writeString(file, source(parity));
    Path classes = Files.createDirectories(dir.resolve("classes"));
    Summaries summaries = TestSummaries.compile(Summaries.Thresholds.DEFAULT, TestSummaries.optionsFor(file.toString()), classes,
      file.toString());
    assertThatCode(() -> CheckVerifier.newVerifier()
      .onFile(file.toString())
      .withClassPath(List.of(classes.toFile()))
      .withCheck((JavaFileScanner) spec.check().apply(() -> summaries))
      .verifyNoIssues()).doesNotThrowAnyException();
  }

  @org.junit.jupiter.api.Test
  void descriptor_regexes_on_long_input() {
    String key = "a/B#m(" + "Ljava/lang/String;".repeat(2_000) + ")Ljava/lang/Long;";
    assertThat(key.matches(".*\\)Ljava/lang/(Long|Integer|Short|Byte|Boolean|Double|Float|Character);")).isTrue();
  }
}
