package io.github.snakeinprod.deepjava;

import io.github.snakeinprod.deepjava.checks.NullReturnDereferenceCheck;
import io.github.snakeinprod.deepjava.prepass.ProjectSummaries;
import java.util.List;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.config.Configuration;
import org.sonar.plugins.java.api.CheckRegistrar;

/** Per module: adds the module's classpath to the shared pre-pass and hands sonar-java the rule instances. */
public class DeepJavaCheckRegistrar implements CheckRegistrar {

  private final ProjectSummaries summaries;

  public DeepJavaCheckRegistrar(ProjectSummaries summaries, Configuration moduleConfig, FileSystem moduleFs) {
    this.summaries = summaries;
    summaries.addModule(moduleFs.baseDir().toPath(), moduleConfig);
  }

  @Override
  public void register(RegistrarContext context) {
    context.registerMainChecks(DeepJavaRulesDefinition.REPOSITORY, List.of(new NullReturnDereferenceCheck(summaries)));
  }
}
