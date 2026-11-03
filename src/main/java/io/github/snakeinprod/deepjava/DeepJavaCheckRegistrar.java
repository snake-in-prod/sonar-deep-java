package io.github.snakeinprod.deepjava;

import io.github.snakeinprod.deepjava.prepass.ProjectSummaries;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.config.Configuration;
import org.sonar.plugins.java.api.CheckRegistrar;
import org.sonar.plugins.java.api.JavaCheck;

/** Per module: adds the module's classpath to the shared pre-pass and hands sonar-java the rule instances. */
public class DeepJavaCheckRegistrar implements CheckRegistrar {

  private final ProjectSummaries summaries;

  public DeepJavaCheckRegistrar(ProjectSummaries summaries, Configuration moduleConfig, FileSystem moduleFs) {
    this.summaries = summaries;
    summaries.addModule(moduleFs.baseDir().toPath(), moduleConfig);
  }

  @Override
  public void register(RegistrarContext context) {
    java.util.List<JavaCheck> main = new java.util.ArrayList<>();
    java.util.List<JavaCheck> test = new java.util.ArrayList<>();
    for (Rules.Spec spec : Rules.ALL) {
      (spec.test() ? test : main).add(spec.check().apply(summaries));
    }
    context.registerMainChecks(DeepJavaRulesDefinition.REPOSITORY, main);
    context.registerTestChecks(DeepJavaRulesDefinition.REPOSITORY, test);
  }
}
