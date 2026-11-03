package io.github.snakeinprod.deepjava;

import io.github.snakeinprod.deepjava.checks.NullReturnDereferenceCheck;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.sonar.api.issue.impact.Severity;
import org.sonar.api.issue.impact.SoftwareQuality;
import org.sonar.api.rules.CleanCodeAttribute;
import org.sonar.api.rules.RuleType;
import org.sonar.api.server.rule.RulesDefinition;

public class DeepJavaRulesDefinition implements RulesDefinition {

  public static final String REPOSITORY = "deepjava";
  public static final List<String> RULE_KEYS = List.of(NullReturnDereferenceCheck.KEY);

  @Override
  public void define(Context context) {
    NewRepository repo = context.createRepository(REPOSITORY, "java").setName("Deep Java");

    repo.createRule(NullReturnDereferenceCheck.KEY)
      .setName("Values that can be null should not be dereferenced across methods")
      .setHtmlDescription(html(NullReturnDereferenceCheck.KEY))
      .setType(RuleType.BUG)
      .setSeverity("MAJOR")
      .setCleanCodeAttribute(CleanCodeAttribute.LOGICAL)
      .addDefaultImpact(SoftwareQuality.RELIABILITY, Severity.HIGH)
      .addCwe(476, 690)
      .setTags("cwe", "nullness", "interprocedural");

    repo.done();
  }

  private static String html(String key) {
    try (InputStream in = DeepJavaRulesDefinition.class.getResourceAsStream("/rules/" + key + ".html")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
