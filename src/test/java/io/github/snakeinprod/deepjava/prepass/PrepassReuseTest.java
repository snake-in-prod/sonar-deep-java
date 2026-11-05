package io.github.snakeinprod.deepjava.prepass;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.snakeinprod.deepjava.DeepJavaPlugin;
import io.github.snakeinprod.deepjava.Rules;
import io.github.snakeinprod.deepjava.TestSummaries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.api.config.Configuration;

/** 100 modules over one shared classpath: one whole-program computation, the same findings as computing per module. */
class PrepassReuseTest {

  @TempDir
  Path root;

  record MapConfig(Map<String, String> values) implements Configuration {
    @Override
    public Optional<String> get(String key) {
      return Optional.ofNullable(values.get(key));
    }

    @Override
    public boolean hasKey(String key) {
      return values.containsKey(key);
    }

    @Override
    public String[] getStringArray(String key) {
      String v = values.get(key);
      return v == null ? new String[0] : v.split(",");
    }
  }

  private MapConfig config(boolean cache, boolean parity) {
    Map<String, String> m = new HashMap<>();
    m.put("sonar.projectBaseDir", root.toString());
    m.put(DeepJavaPlugin.CACHE_DIR, root.resolve("cache").toString());
    m.put(DeepJavaPlugin.PREPASS_CACHE, String.valueOf(cache));
    m.put(DeepJavaPlugin.REFERENCE_PARITY, String.valueOf(parity));
    return new MapConfig(m);
  }

  private MapConfig module(int i) throws Exception {
    Map<String, String> m = new HashMap<>();
    m.put("sonar.java.binaries", "target/classes");
    // every module brings its own library directory and test classes, as Maven modules do
    Path lib = Files.createDirectories(root.resolve("libs/m" + i));
    m.put("sonar.java.libraries", lib.toString());
    Path tests = Files.createDirectories(root.resolve("m" + i + "/target/test-classes"));
    m.put("sonar.java.test.binaries", tests.toString());
    return new MapConfig(m);
  }

  /** Findings of every rule in every source file, as the checks read them. */
  private static List<String> findings(Summaries s) {
    List<String> out = new ArrayList<>();
    for (ClassFacts c : s.classes().values()) {
      for (Rules.Spec r : Rules.ALL) {
        s.findings(r.key(), c.sourcePath()).forEach(f -> out.add(f.toString()));
      }
    }
    out.sort(null);
    return out;
  }

  private List<String> run(boolean cache, boolean parity) throws Exception {
    List<String> last = null;
    Summaries first = null;
    for (int i = 0; i < 100; i++) {
      // a new instance per module, as if the scanner created one per module: only the JVM-wide memo can help
      ProjectSummaries p = new ProjectSummaries(config(cache, parity));
      p.addModule(root, module(i));
      Summaries s = p.get();
      if (first == null) {
        first = s;
      } else if (cache) {
        assertThat(s).isSameAs(first);
      }
      last = findings(s);
    }
    return last;
  }

  @Test
  void one_computation_for_100_modules_and_the_same_findings() throws Exception {
    Path classes = Files.createDirectories(root.resolve("target/classes"));
    TestSummaries.compile(classes, "src/test/files/rules/TaintedSize.java", "src/test/files/rules/LockOrderCycle.java",
      "src/test/files/rules/InconsistentLocking.java", "src/test/files/rules/CodeAfterAlwaysThrows.java");
    for (boolean parity : new boolean[] {false, true}) {
      int before = ProjectSummaries.computations;
      List<String> cached = run(true, parity);
      assertThat(ProjectSummaries.computations - before).isEqualTo(1);
      before = ProjectSummaries.computations;
      List<String> uncached = run(false, parity);
      assertThat(ProjectSummaries.computations - before).isEqualTo(100);
      assertThat(cached).isNotEmpty().isEqualTo(uncached);
    }
  }
}
