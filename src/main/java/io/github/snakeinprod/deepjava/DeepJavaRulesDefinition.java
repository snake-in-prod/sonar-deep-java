package io.github.snakeinprod.deepjava;

import java.util.List;
import org.sonar.api.issue.impact.Severity;
import org.sonar.api.issue.impact.SoftwareQuality;
import org.sonar.api.rules.CleanCodeAttribute;
import org.sonar.api.rules.RuleType;
import org.sonar.api.server.rule.RulesDefinition;

public class DeepJavaRulesDefinition implements RulesDefinition {

  public static final String REPOSITORY = "deepjava";
  public static final List<String> RULE_KEYS = Rules.ALL.stream().map(Rules.Spec::key).toList();

  @Override
  public void define(Context context) {
    NewRepository repo = context.createRepository(REPOSITORY, "java").setName("Deep Java");
    for (Rules.Spec spec : Rules.ALL) {
      NewRule rule = repo.createRule(spec.key())
        .setName(spec.name())
        .setHtmlDescription(spec.html())
        .setType(spec.type())
        .setSeverity(spec.severity())
        .setTags(spec.tags());
      if (spec.cwe().length > 0) {
        rule.addCwe(spec.cwe());
      }
      SoftwareQuality quality = switch (spec.type()) {
        case VULNERABILITY, SECURITY_HOTSPOT -> SoftwareQuality.SECURITY;
        case BUG -> SoftwareQuality.RELIABILITY;
        default -> SoftwareQuality.MAINTAINABILITY;
      };
      rule.addDefaultImpact(quality, "CRITICAL".equals(spec.severity()) ? Severity.HIGH : Severity.MEDIUM)
        .setCleanCodeAttribute(spec.type() == RuleType.VULNERABILITY ? CleanCodeAttribute.COMPLETE
          : spec.type() == RuleType.BUG ? CleanCodeAttribute.LOGICAL : CleanCodeAttribute.CLEAR);
    }
    repo.done();
  }
}
