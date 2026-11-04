package io.github.snakeinprod.deepjava.prepass;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassFactsTest {

  @TempDir
  Path dir;

  @Test
  void cache_round_trips_through_the_filtered_stream() throws Exception {
    byte[] bytes = Files.readAllBytes(Path.of("target/classes/io/github/snakeinprod/deepjava/prepass/Summaries.class"));
    ClassFacts facts = BytecodeScanner.scan(bytes, 20000);
    Path file = dir.resolve("cache.bin");
    ProjectSummaries.writeCache(file, Map.of("k", facts));
    assertThat(ProjectSummaries.readCache(file)).containsEntry("k", facts);
  }

  /** A local set to null and then compared with null must not cost the method its summary. */
  static class ExplicitNull {
    String f(String s) {
      String v = null;
      if (s.isEmpty()) {
        v = s;
      }
      if (v == null) {
        return s.trim();
      }
      return v;
    }
  }

  @Test
  void explicit_null_local_keeps_the_summary() throws Exception {
    byte[] bytes = Files.readAllBytes(Path.of("target/test-classes/io/github/snakeinprod/deepjava/prepass/ClassFactsTest$ExplicitNull.class"));
    ClassFacts facts = BytecodeScanner.scan(bytes, 20000);
    assertThat(facts.methods().get("f(Ljava/lang/String;)Ljava/lang/String;").nulls().invokedNames()).contains("trim");
  }
}
