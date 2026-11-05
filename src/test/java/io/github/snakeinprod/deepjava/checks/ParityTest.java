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

  @Test
  void statistics_without_test_callers() throws Exception {
    verify("src/test/files/parity/RepositoryFinders.java", s -> new NullCheckedAtMostCallSitesCheck(() -> s),
      "src/test/files/paritytests/RepositoryFindersTest.java");
  }

  @Test
  void annotated_library_nullables_and_their_wrappers() throws Exception {
    verify("src/test/files/parity/AnnotatedLibraries.java", s -> new NullableDereferenceCheck(() -> s));
  }

  @Test
  void loop_conditions_are_loop_bounds() throws Exception {
    verify("src/test/files/parity/LoopBounds.java", s -> (JavaFileScanner) TaintChecks.create("TaintedLoopBound", () -> s));
  }

  @Test
  void paths_reported_where_the_file_is_used() throws Exception {
    verify("src/test/files/parity/PathUses.java", s -> (JavaFileScanner) TaintChecks.create("TaintedPath", () -> s));
  }

  @Test
  void null_checks_in_catch_of_values_assigned_after_the_last_throw() throws Exception {
    verify("src/test/files/parity/CatchNullChecks.java", s -> new UnreachableCodeCheck(() -> s));
  }

  @Test
  void declared_nullables_passed_on_in_lambdas_and_after_a_check() throws Exception {
    verify("src/test/files/parity/DeclaredPassed.java", s -> new NullableDereferenceCheck(() -> s));
  }

  @Test
  void statistics_in_lambdas() throws Exception {
    verify("src/test/files/parity/LambdaStatistics.java", s -> new NullCheckedAtMostCallSitesCheck(() -> s));
  }

  @Test
  void project_future_overrides_its_model() throws Exception {
    verify("src/test/files/parity/FutureSubclass.java", s -> new NullReturnDereferenceCheck(() -> s));
  }

  @Test
  void ignored_jdk_status_checked_almost_everywhere() throws Exception {
    verify("src/test/files/parity/IgnoredDelete.java", s -> new FindingChecks.IgnoredResult(() -> s));
  }

  @Test
  void octal_escapes_and_bare_single_character_alternatives() throws Exception {
    verify("src/test/files/parity/RegexParity.java", s -> new RegexPitfallCheck(() -> s));
  }

  @Test
  void last_else_if_equal_to_final_else() throws Exception {
    verify("src/test/files/parity/ElseCopies.java", s -> new IdenticalBranchesCheck(() -> s));
  }

  @Test
  void wrapper_in_a_local_never_closed() throws Exception {
    verify("src/test/files/parity/LocalWrappers.java", s -> new ResourceLeakCheck(() -> s));
  }

  @Test
  void secret_compared_with_a_constant() throws Exception {
    verify("src/test/files/parity/ConstantSecrets.java", s -> new TimingUnsafeSecretComparisonCheck(() -> s));
  }

  @Test
  void branch_passes_a_sibling_and_twin_returned() throws Exception {
    verify("src/test/files/parity/BranchCopies.java", s -> new CopyPasteCheck(() -> s));
  }

  @Test
  void lombok_equals_without_included_members() throws Exception {
    verify("src/test/files/parity/LombokEquals.java", s -> new UnreachableCodeCheck(() -> s));
  }

  @Test
  void char_range_inside_a_longer_condition() throws Exception {
    verify("src/test/files/parity/InvariantNested.java", s -> new InvariantResultCheck(() -> s));
  }

  @Test
  void synchronized_methods_calling_each_other() throws Exception {
    verify("src/test/files/parity/SynchronizedCallback.java", s -> new FindingChecks.LockOrder(() -> s));
  }

  @Test
  void zero_helper_passed_to_a_dividing_helper() throws Exception {
    verify("src/test/files/parity/ZeroPassedOn.java", s -> new DivisionByPossibleZeroCheck(() -> s));
  }

  @Test
  void null_local_as_tenth_argument() throws Exception {
    verify("src/test/files/parity/NullTenthArgument.java", s -> new NullArgumentCheck(() -> s));
  }

  @Test
  void search_index_passed_on_after_branches() throws Exception {
    verify("src/test/files/parity/NegativeLater.java", s -> new NegativeIndexCheck(() -> s));
  }

  @Test
  void copy_paste_roles_repeated_in_the_block() throws Exception {
    verify("src/test/files/parity/CopyPasteRoles.java", s -> new CopyPasteCheck(() -> s));
  }

  @Test
  void nullable_from_libraries_and_generated_code() throws Exception {
    verify("src/test/files/parity/NullableSources.java", s -> new NullableDereferenceCheck(() -> s));
  }

  @Test
  void nullable_passed_to_dereferencing_methods() throws Exception {
    verify("src/test/files/parity/NullablePassed.java", s -> new NullableDereferenceCheck(() -> s));
  }

  @Test
  void statistical_getters_in_lambdas_arguments_and_require_non_null() throws Exception {
    verify("src/test/files/parity/StatisticalGetters.java", s -> new NullCheckedAtMostCallSitesCheck(() -> s));
  }

  @Test
  void lock_order_through_interfaces_and_helpers() throws Exception {
    verify("src/test/files/parity/LockOrderInterfaces.java", s -> new FindingChecks.LockOrder(() -> s));
  }

  @Test
  void null_returns_of_future_get_and_helpers() throws Exception {
    verify("src/test/files/parity/NullReturns.java", s -> new NullReturnDereferenceCheck(() -> s), "src/test/files/parity/NullableSources.java");
  }

  @Test
  void division_by_a_helper_returning_zero() throws Exception {
    verify("src/test/files/parity/ZeroHelpers.java", s -> new DivisionByPossibleZeroCheck(() -> s));
  }

  @Test
  void negative_index_into_project_methods() throws Exception {
    verify("src/test/files/parity/NegativeIndexHelpers.java", s -> new NegativeIndexCheck(() -> s));
  }

  @Test
  void unrelated_key_of_a_project_key_type() throws Exception {
    verify("src/test/files/parity/ProjectKeys.java", s -> new UnrelatedKeyTypeCheck(() -> s));
  }

  @Test
  void removal_in_key_set_for_each() throws Exception {
    verify("src/test/files/parity/KeySetForEach.java", s -> new ModifiedInForEachCheck(() -> s));
  }

  @Test
  void main_not_catching_library_exceptions() throws Exception {
    verify("src/test/files/parity/MainEntry.java", s -> new FindingChecks.UncheckedInEntryPoint(() -> s));
  }

  @Test
  void null_assigned_in_a_branch_then_passed() throws Exception {
    verify("src/test/files/parity/NullBranchPassed.java", s -> new NullAssignedThenDereferencedCheck(() -> s));
  }

  @Test
  void request_values_into_cookies_through_helpers() throws Exception {
    verify("src/test/files/parity/CookieHelpers.java", s -> (JavaFileScanner) TaintChecks.create("TaintedHeader", () -> s));
  }

  @Test
  void configured_file_names_to_readers() throws Exception {
    verify("src/test/files/parity/ConfigFiles.java", s -> (JavaFileScanner) TaintChecks.create("TaintedPath", () -> s));
  }

  @Test
  void parsed_record_integers_as_indexes_through_helpers() throws Exception {
    verify("src/test/files/parity/RecordIndexes.java", s -> (JavaFileScanner) TaintChecks.create("TaintedSize", () -> s));
  }

  @Test
  void copy_paste_twins() throws Exception {
    verify("src/test/files/parity/CopyPasteTwins.java", s -> new CopyPasteCheck(() -> s));
  }

  @Test
  void statement_tokens_of_a_huge_literal() {
    String literal = "\"" + "<a href=\\\"x\\\">\\n".repeat(2_000) + "\"";
    org.assertj.core.api.Assertions.assertThat(CopyPasteCheck.tokens("m.put(\"k\", " + literal + ");")).hasSize(9);
  }

  private void verify(String file, java.util.function.Function<Summaries, JavaFileScanner> check, String... more) throws Exception {
    String[] sources = java.util.stream.Stream.concat(java.util.stream.Stream.of(file), java.util.Arrays.stream(more)).toArray(String[]::new);
    Summaries summaries = TestSummaries.compile(Summaries.Thresholds.DEFAULT, TestSummaries.optionsFor(file), classes, sources);
    CheckVerifier.newVerifier()
      .onFile(file)
      .withClassPath(List.of(classes.toFile()))
      .withCheck(check.apply(summaries))
      .verifyIssues();
  }
}
