package io.github.snakeinprod.deepjava.prepass;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.snakeinprod.deepjava.DeepJavaPlugin;
import io.github.snakeinprod.deepjava.TestSummaries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.sonar.api.config.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSummariesTest {

  @TempDir
  Path root;

  @Test
  void discovers_maven_and_gradle_output_dirs_only() throws Exception {
    Path maven = Files.createDirectories(root.resolve("core/target/classes/a"));
    Path gradle = Files.createDirectories(root.resolve("api/build/classes/java/main"));
    Files.createDirectories(root.resolve("core/target/test-classes"));
    Files.createDirectories(root.resolve("api/build/classes/java/test"));
    Files.createDirectories(root.resolve("node_modules/x/target/classes"));
    Files.createDirectories(root.resolve("core/src/main/target/classes"));

    assertThat(ProjectSummaries.discoverOutputDirs(root))
      .containsExactlyInAnyOrder(maven.getParent().toAbsolutePath().normalize(), gradle.toAbsolutePath().normalize());
  }

  @Test
  void resolves_plain_paths_and_globs() throws Exception {
    Files.createDirectories(root.resolve("lib/sub"));
    Files.createFile(root.resolve("lib/a.jar"));
    Files.createFile(root.resolve("lib/sub/b.jar"));
    Files.createFile(root.resolve("lib/c.txt"));

    assertThat(ProjectSummaries.resolve(root, new String[] {"lib/*.jar", " ", "missing", "lib/c.txt"}))
      .containsExactlyInAnyOrder(root.resolve("lib/a.jar"), root.resolve("lib/c.txt"));
    assertThat(ProjectSummaries.resolve(root, new String[] {"lib/**.jar"}))
      .containsExactlyInAnyOrder(root.resolve("lib/a.jar"), root.resolve("lib/sub/b.jar"));
  }

  @Test
  void cache_key_reads_single_value_properties_without_get_string_array() throws Exception {
    Map<String, String> props = Map.of("sonar.projectBaseDir", root.toString(), DeepJavaPlugin.MAX_STEPS, "100",
      DeepJavaPlugin.NULLABLE_ANNOTATIONS, "A, B");
    String key = new ProjectSummaries(config(props)).key();

    assertThat(new ProjectSummaries(config(props)).key()).isEqualTo(key);
    assertThat(new ProjectSummaries(config(Map.of("sonar.projectBaseDir", root.toString(), DeepJavaPlugin.MAX_STEPS, "200",
      DeepJavaPlugin.NULLABLE_ANNOTATIONS, "A, B"))).key()).isNotEqualTo(key);
    assertThat(new ProjectSummaries(config(Map.of("sonar.projectBaseDir", root.toString(), DeepJavaPlugin.MAX_STEPS, "100",
      DeepJavaPlugin.NULLABLE_ANNOTATIONS, "A"))).key()).isNotEqualTo(key);
  }

  private Summaries prepass(boolean diskCache) {
    // no JVM memo, so every call computes
    ProjectSummaries p = new ProjectSummaries(new PrepassReuseTest.MapConfig(Map.of("sonar.projectBaseDir", root.toString(),
      DeepJavaPlugin.CACHE_DIR, root.resolve("cache").toString(), DeepJavaPlugin.PREPASS_CACHE, "false",
      DeepJavaPlugin.CACHE, String.valueOf(diskCache))));
    p.addModule(root, new PrepassReuseTest.MapConfig(Map.of("sonar.java.binaries", "target/classes")));
    Summaries s = p.get();
    ProjectSummaries.awaitCacheWrite();
    return s;
  }

  private Path cacheFile() throws Exception {
    try (var files = Files.list(root.resolve("cache"))) {
      return files.filter(f -> f.toString().endsWith(".bin")).findFirst().orElseThrow();
    }
  }

  @Test
  void disk_cache_setting_turns_reads_and_writes_off_and_an_unchanged_cache_is_not_rewritten() throws Exception {
    TestSummaries.compile(Files.createDirectories(root.resolve("target/classes")), "src/test/files/rules/TaintedSize.java");
    assertThat(prepass(false).classes()).isNotEmpty();
    assertThat(root.resolve("cache")).doesNotExist();

    Summaries real = prepass(true);
    Path file = cacheFile();
    // rename every cached class: a run that reads the cache sees the new names
    Map<String, ClassFacts> fake = new HashMap<>();
    ProjectSummaries.readCache(file).forEach((k, f) -> fake.put(k, new ClassFacts("fake/" + f.name(), f.superName(), f.interfaces(),
      f.sourceFile(), f.access(), f.annotations(), f.fields(), f.methods())));
    ProjectSummaries.writeCache(file, fake);
    FileTime written = FileTime.fromMillis(1_000_000_000_000L);
    Files.setLastModifiedTime(file, written);

    assertThat(prepass(true).classes().keySet()).allMatch(n -> n.startsWith("fake/"));
    assertThat(prepass(false).classes().keySet()).isEqualTo(real.classes().keySet());
    assertThat(Files.getLastModifiedTime(file)).isEqualTo(written);
    assertThat(ProjectSummaries.readCache(file)).isEqualTo(fake);
  }

  @Test
  void a_failed_cache_write_leaves_the_previous_file_and_no_temporary_file() throws Exception {
    byte[] bytes = Files.readAllBytes(Path.of("target/classes/io/github/snakeinprod/deepjava/prepass/Summaries.class"));
    ClassFacts facts = BytecodeScanner.scan(bytes, 20000);
    Path file = root.resolve("cache/c.bin");
    ProjectSummaries.writeCacheInBackground(file, Map.of("k", facts));
    ProjectSummaries.awaitCacheWrite();
    assertThat(ProjectSummaries.readCache(file)).isEqualTo(Map.of("k", facts));

    List<String> unserializable = new AbstractList<>() {
      @Override
      public String get(int index) {
        return "x";
      }

      @Override
      public int size() {
        return 1;
      }
    };
    ClassFacts broken = new ClassFacts("b", null, unserializable, null, 0, Map.of(), Map.of(), Map.of());
    Map<String, ClassFacts> entries = new HashMap<>();
    entries.put("k", facts);
    entries.put("b", broken);
    ProjectSummaries.writeCache(file, entries);

    assertThat(ProjectSummaries.readCache(file)).isEqualTo(Map.of("k", facts));
    try (var files = Files.list(file.getParent())) {
      assertThat(files).containsExactly(file);
    }
  }

  /** Fails like the scanner warns: getStringArray on a property not declared multi-value. */
  private static Configuration config(Map<String, String> props) {
    Set<String> multi = Set.of(DeepJavaPlugin.NULLABLE_ANNOTATIONS, DeepJavaPlugin.NULLABLE_IGNORED, DeepJavaPlugin.MODELS);
    return new Configuration() {
      @Override
      public Optional<String> get(String key) {
        return Optional.ofNullable(props.get(key));
      }

      @Override
      public boolean hasKey(String key) {
        return props.containsKey(key);
      }

      @Override
      public String[] getStringArray(String key) {
        assertThat(multi).as("getStringArray(" + key + ")").contains(key);
        return get(key).map(v -> v.split("\\s*,\\s*")).orElse(new String[0]);
      }
    };
  }
}
