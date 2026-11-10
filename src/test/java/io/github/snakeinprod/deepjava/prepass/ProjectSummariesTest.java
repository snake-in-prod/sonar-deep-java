package io.github.snakeinprod.deepjava.prepass;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.snakeinprod.deepjava.DeepJavaPlugin;
import java.nio.file.Files;
import java.nio.file.Path;
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
