package io.github.snakeinprod.deepjava;

import io.github.snakeinprod.deepjava.prepass.ProjectSummaries;
import org.sonar.api.Plugin;
import org.sonar.api.config.PropertyDefinition;

public class DeepJavaPlugin implements Plugin {

  public static final String MODELS = "sonar.deepjava.models";
  public static final String CACHE_DIR = "sonar.deepjava.cacheDir";
  public static final String MAX_STEPS = "sonar.deepjava.maxStepsPerMethod";
  public static final String MAX_TARGETS = "sonar.deepjava.maxVirtualTargets";
  public static final String MIN_SITES = "sonar.deepjava.statistics.minCallSites";
  public static final String MIN_RATIO = "sonar.deepjava.statistics.minRatio";
  public static final String NULLABLE_ANNOTATIONS = "sonar.deepjava.nullableAnnotations";
  public static final String NULLABLE_IGNORED = "sonar.deepjava.nullableAnnotations.ignored";
  public static final String DATA_SOURCES = "sonar.deepjava.taint.dataSources";
  public static final String LOG_PARAMETERS_SAFE = "sonar.deepjava.taint.logParametersSafe";
  public static final String REFERENCE_PARITY = "sonar.deepjava.referenceParity";
  public static final String PREPASS_CACHE = "sonar.deepjava.prepassCache";
  public static final String CACHE = "sonar.deepjava.cache";

  @Override
  public void define(Context context) {
    context.addExtensions(
      DeepJavaRulesDefinition.class,
      DeepJavaProfileRegistrar.class,
      ProjectSummaries.class,
      DeepJavaCheckRegistrar.class,
      prop(MODELS, "Library models", "Extra JSON library model files (paths relative to the project base directory).", null).multiValues(true).build(),
      prop(CACHE_DIR, "Summary cache directory", "Where per-class summaries are cached between analyses. Default: ~/.sonar/deepjava-cache", null).build(),
      prop(CACHE, "Summary cache on disk", "Read and write the per-class summary cache in the cache directory; false for CI runs "
        + "that never keep it.", "true").type(org.sonar.api.PropertyType.BOOLEAN).build(),
      prop(MAX_STEPS, "Max steps per method", "Data-flow budget per method; methods over budget get no summary.", "20000").build(),
      prop(MAX_TARGETS, "Max virtual call targets", "Virtual calls with more implementations than this are ignored.", "8").build(),
      prop(MIN_SITES, "Statistics: min call sites", "A null returned by project code is reported only if the method has at least this many call sites.", "5").build(),
      prop(MIN_RATIO, "Statistics: min checked ratio", "...and its result is null-checked at least at this share of them (0-1).", "0.8").build(),
      prop(NULLABLE_ANNOTATIONS, "Nullable annotations", "Annotations that mark a nullable method or field. A simple name matches any package.",
        "Nullable,CheckForNull,NullableDecl").multiValues(true).build(),
      prop(NULLABLE_IGNORED, "Ignored nullable annotations", "Qualified annotation names to ignore, e.g. org.apache.avro.reflect.Nullable.", null)
        .multiValues(true).build(),
      prop(DATA_SOURCES, "Binary and text input as size sources", "Integers read from buffers, streams, readers and parsers count as "
        + "untrusted sizes, indexes and loop bounds.", "true").type(org.sonar.api.PropertyType.BOOLEAN).build(),
      prop(LOG_PARAMETERS_SAFE, "Parameterised log arguments are safe", "log.info(\"x {}\", value) is not reported as log injection; "
        + "set to false if the log layout does not encode CR/LF.", "true").type(org.sonar.api.PropertyType.BOOLEAN).build(),
      prop(REFERENCE_PARITY, "Reference parity", "Also report what whole-program reference analyzers report where this plugin "
        + "deliberately stays quiet: Objects.requireNonNull on nullable values, unreachable code in Lombok-generated members, "
        + "statistical null checks of getters.", "false").type(org.sonar.api.PropertyType.BOOLEAN).build(),
      prop(PREPASS_CACHE, "Reuse the pre-pass across modules", "Compute the whole-program pre-pass once per scanner run and reuse it "
        + "in every module with the same compiled classes; false recomputes it whenever a module adds classes, libraries or tests.",
        "true").type(org.sonar.api.PropertyType.BOOLEAN).build());
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
