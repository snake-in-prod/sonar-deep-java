package rules;

import java.util.HashMap;
import java.util.Map;

class Registry {
  private final Map<String, String> users = new HashMap<>();
  private final Map<String, String> roles = new HashMap<>();

  void addRole(String k) {
    roles.put(k, "r");
  }

  boolean known(String id) {
    return users.containsKey(id); // Noncompliant
  }

  boolean hasRole(String id) {
    return roles.containsKey(id);
  }
}
