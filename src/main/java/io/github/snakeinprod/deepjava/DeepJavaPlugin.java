package io.github.snakeinprod.deepjava;

import io.github.snakeinprod.deepjava.prepass.ProjectSummaries;
import org.sonar.api.Plugin;
import org.sonar.api.config.PropertyDefinition;

public class DeepJavaPlugin implements Plugin {

  public static final String MODELS = "sonar.deepjava.models";
  public static final String CACHE_DIR = "sonar.deepjava.cacheDir";
  public static final String MAX_STEPS = "sonar.deepjava.maxStepsPerMethod";
  public static final String MAX_TARGETS = "sonar.deepjava.maxVirtualTargets";

  @Override
  public void define(Context context) {
    context.addExtensions(
      DeepJavaRulesDefinition.class,
      DeepJavaProfileRegistrar.class,
      ProjectSummaries.class,
      DeepJavaCheckRegistrar.class,
      prop(MODELS, "Library models", "Extra JSON library model files (paths relative to the project base directory).", null).multiValues(true).build(),
      prop(CACHE_DIR, "Summary cache directory", "Where per-class summaries are cached between analyses. Default: ~/.sonar/deepjava-cache", null).build(),
      prop(MAX_STEPS, "Max steps per method", "Data-flow budget per method; methods over budget get no summary.", "20000").build(),
      prop(MAX_TARGETS, "Max virtual call targets", "Virtual calls with more implementations than this are ignored.", "8").build());
  }

  private static PropertyDefinition.Builder prop(String key, String name, String description, String defaultValue) {
    PropertyDefinition.Builder b = PropertyDefinition.builder(key)
      .name(name)
      .description(description)
      .category("Deep Java")
      .onConfigScopes(PropertyDefinition.ConfigScope.PROJECT);
    return defaultValue == null ? b : b.defaultValue(defaultValue);
  }
}
