package io.github.snakeinprod.deepjava;

import org.sonar.api.rule.RuleKey;
import org.sonar.plugins.java.api.ProfileRegistrar;

/** Adds the plugin rules to the default Java profile ("Sonar way"). */
public class DeepJavaProfileRegistrar implements ProfileRegistrar {
  @Override
  public void register(RegistrarContext context) {
    context.registerDefaultQualityProfileRules(
      DeepJavaRulesDefinition.RULE_KEYS.stream().filter(k -> !Rules.NOT_IN_DEFAULT_PROFILE.contains(k))
        .map(k -> RuleKey.of(DeepJavaRulesDefinition.REPOSITORY, k)).toList());
  }
}
