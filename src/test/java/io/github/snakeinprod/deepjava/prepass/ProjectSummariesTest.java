package io.github.snakeinprod.deepjava.prepass;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
