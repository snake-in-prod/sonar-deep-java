package nullness;

import java.util.HashMap;
import java.util.Map;

public class Config {
  private final Map<String, String> props = new HashMap<>();

  public Map<String, String> props() {
    return props;
  }

  public String host() {
    return props().get("host");
  }

  public String hostOrDefault() {
    String h = props.get("host");
    if (h == null) {
      return "localhost";
    }
    return h;
  }

  public String find(String key) {
    if (key.isEmpty()) {
      return null;
    }
    return key;
  }

  public String findViaHelper(String key) {
    return find(key);
  }

  public String checked(String key) {
    String v = find(key);
    return v != null ? v : "";
  }

  public String guardedGet(String key) {
    if (!props.containsKey(key)) {
      throw new IllegalArgumentException(key);
    }
    return props.get(key);
  }

  public String fromCatch(String s) {
    try {
      return s.trim();
    } catch (RuntimeException e) {
      return null;
    }
  }

  public static String never(String s) {
    return s + "!";
  }
}
