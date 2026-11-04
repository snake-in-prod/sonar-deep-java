package io.github.snakeinprod.deepjava.checks;

import io.github.snakeinprod.deepjava.TestSummaries;
import io.github.snakeinprod.deepjava.prepass.Summaries;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.java.checks.verifier.CheckVerifier;
import org.sonar.plugins.java.api.JavaFileScanner;

/** Rules in {@code sonar.deepjava.referenceParity} mode, one file each under {@code src/test/files/parity}. */
class ParityTest {

  @TempDir
  Path classes;

  @Test
  void lombok_null_compared_field_passed_to_dereferencing_method() throws Exception {
    verify("src/test/files/parity/LombokFieldPassed.java", s -> new NullArgumentCheck(() -> s));
  }

  @Test
  void null_dereferenced_in_a_later_branch() throws Exception {
    verify("src/test/files/parity/NullInLaterBranch.java", s -> new NullAssignedThenDereferencedCheck(() -> s));
  }

  @Test
  void parsed_numbers_comparisons_and_sleeps() throws Exception {
    verify("src/test/files/parity/ParsedSizes.java", s -> (JavaFileScanner) TaintChecks.create("TaintedSize", () -> s));
  }

  @Test
  void public_parameters_to_paths() throws Exception {
    verify("src/test/files/parity/PublicPaths.java", s -> (JavaFileScanner) TaintChecks.create("TaintedPath", () -> s));
  }

  @Test
  void numbers_into_cookies() throws Exception {
    verify("src/test/files/parity/CookieNumbers.java", s -> (JavaFileScanner) TaintChecks.create("TaintedHeader", () -> s));
  }

  @Test
  void copy_paste_in_branches() throws Exception {
    verify("src/test/files/parity/Branches.java", s -> new CopyPasteCheck(() -> s));
  }

  @Test
  void generic_dao_key() throws Exception {
    verify("src/test/files/parity/GenericKeys.java", s -> new UnrelatedKeyTypeCheck(() -> s));
  }

  @Test
  void same_key_put_while_iterating() throws Exception {
    verify("src/test/files/parity/SameKeyPut.java", s -> new ModifiedInForEachCheck(() -> s));
  }

  @Test
  void single_dot_regex() throws Exception {
    verify("src/test/files/parity/DotRegex.java", s -> new RegexPitfallCheck(() -> s));
  }

  @Test
  void statistical_null_checks_at_low_ratios() throws Exception {
    verify("src/test/files/parity/NullCheckedLowRatio.java", s -> new NullCheckedAtMostCallSitesCheck(() -> s));
  }

  @Test
  void factory_returning_null() throws Exception {
    verify("src/test/files/parity/FactoryNull.java", s -> new NullReturnDereferenceCheck(() -> s));
  }

  @Test
  void multipart_file_names_to_paths() throws Exception {
    verify("src/test/files/parity/Uploads.java", s -> (JavaFileScanner) TaintChecks.create("TaintedPath", () -> s));
  }

  @Test
  void parenthesised_arithmetic_in_a_ternary_condition() throws Exception {
    verify("src/test/files/parity/Misc.java", s -> new TernaryPrecedenceCheck(() -> s));
  }

  @Test
  void stream_never_closed() throws Exception {
    verify("src/test/files/parity/Unclosed.java", s -> new ResourceFromHelperCheck(() -> s));
  }

  private void verify(String file, java.util.function.Function<Summaries, JavaFileScanner> check) throws Exception {
    Summaries summaries = TestSummaries.compile(Summaries.Thresholds.DEFAULT, TestSummaries.optionsFor(file), classes, file);
    CheckVerifier.newVerifier()
      .onFile(file)
      .withClassPath(List.of(classes.toFile()))
      .withCheck(check.apply(summaries))
      .verifyIssues();
  }
}
