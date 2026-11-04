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

  @Test
  void nullable_annotations_and_lombok_getters() throws Exception {
    verify("src/test/files/nullness/LombokNullable.java");
  }

  @Test
  void explicit_local_null_through_delegation() throws Exception {
    verify("src/test/files/nullness/LocalNullDelegation.java");
  }

  private void verify(String file) throws Exception {
    Summaries summaries = TestSummaries.compile(Summaries.Thresholds.DEFAULT, TestSummaries.optionsFor(file), classes, file);
    CheckVerifier.newVerifier()
      .onFile(file)
      .withClassPath(List.of(classes.toFile()))
      .withCheck(new NullReturnDereferenceCheck(() -> summaries))
      .verifyIssues();
  }

  @Test
  void nullable_libraries_models_and_chains() throws Exception {
    verify("src/test/files/nullness/LibraryNullable.java");
  }

  @Test
  void mappers_futures_and_collections() throws Exception {
    verify("src/test/files/nullness/Delegates.java");
  }

  @Test
  void implicit_dereferences() throws Exception {
    verify("src/test/files/nullness/Implicit.java");
  }

  @Test
  void parity_counts_require_non_null() throws Exception {
    verify("src/test/files/nullness/ParityRequireNonNull.java");
  }

  @Test
  void project_nulls_need_call_site_statistics() throws Exception {
    Summaries summaries = TestSummaries.compile(new Summaries.Thresholds(8, 5, 0.8), classes, "src/test/files/stats/Stats.java");
    CheckVerifier.newVerifier()
      .onFile("src/test/files/stats/Stats.java")
      .withClassPath(List.of(classes.toFile()))
      .withCheck(new NullReturnDereferenceCheck(() -> summaries))
      .verifyIssues();
  }
}
