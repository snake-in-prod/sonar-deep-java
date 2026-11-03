package io.github.snakeinprod.deepjava;

import io.github.snakeinprod.deepjava.checks.NullReturnDereferenceCheck;
import io.github.snakeinprod.deepjava.prepass.ProjectSummaries;
import java.util.List;
import org.sonar.plugins.java.api.CheckRegistrar;

/** Hands sonar-java the rule instances, wired to the shared pre-pass. */
public class DeepJavaCheckRegistrar implements CheckRegistrar {

  private final ProjectSummaries summaries;

  public DeepJavaCheckRegistrar(ProjectSummaries summaries) {
    this.summaries = summaries;
  }

  @Override
  public void register(RegistrarContext context) {
    context.registerMainChecks(DeepJavaRulesDefinition.REPOSITORY, List.of(new NullReturnDereferenceCheck(summaries)));
  }
}
