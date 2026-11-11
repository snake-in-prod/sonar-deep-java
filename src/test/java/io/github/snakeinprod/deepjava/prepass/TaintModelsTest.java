package io.github.snakeinprod.deepjava.prepass;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaintModelsTest {

  /** Wildcard answers are memoized: a repeated lookup, hit or miss, answers as the first one did. */
  @Test
  void wildcard_lookups_answer_the_same_when_repeated() {
    TaintModels m = TaintModels.load(java.util.List.of());
    for (int i = 0; i < 2; i++) {
      assertThat(m.source("com/acme/Db", "getPassword")).isEqualTo("SECRET");
      assertThat(m.source("com/acme/Db", "getName")).isNull();
      assertThat(m.isSanitizer("com/acme/Html", "escapeHtml")).isTrue();
      assertThat(m.isSanitizer("com/acme/Html", "render")).isFalse();
    }
  }
}
