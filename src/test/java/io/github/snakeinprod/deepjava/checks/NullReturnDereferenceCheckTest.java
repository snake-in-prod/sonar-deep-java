package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.TestSummaries;
import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.java.checks.verifier.CheckVerifier;

class NullReturnDereferenceCheckTest {

  @TempDir
  Path classes;

  @Test
  void interprocedural_and_library_nulls() throws Exception {
    Summaries summaries = TestSummaries.compile(classes, "src/test/files/nullness/Config.java", "src/test/files/nullness/Client.java");
    CheckVerifier.newVerifier()
      .onFile("src/test/files/nullness/Client.java")
      .withClassPath(List.of(classes.toFile()))
      .withCheck(new NullReturnDereferenceCheck(() -> summaries))
      .verifyIssues();
  }
}
