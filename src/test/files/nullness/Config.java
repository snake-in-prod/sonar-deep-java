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

  // null only when the argument is null: not a null-returning method

  public static String trimOrNull(String s) {
    if (s == null) {
      return null;
    }
    return s.trim();
  }

  public static Class<?> classOf(Object o) {
    return o == null ? null : o.getClass();
  }

  public static String[] fill(String[] a) {
    if (a != null) {
      java.util.Arrays.fill(a, "");
    }
    return a;
  }

  private static final Map<String, String> TABLE = new HashMap<>();

  public static String lookup(String k) {
    return TABLE.get(k);
  }

  public Config copy() {
    try {
      return (Config) clone();
    } catch (CloneNotSupportedException e) {
      return null;
    }
  }

  public String localNull(boolean b) {
    String r = null;
    if (b) {
      r = "x";
    }
    return r;
  }

  // default meant to be overridden (see NamedConfig)
  public String name() {
    return null;
  }

  private void fail(String message) {
    throw new IllegalStateException(message);
  }

  public String failing(String s) {
    if (s.isEmpty()) {
      fail("empty");
      return null; // never gets here
    }
    return s;
  }

  private String cached;

  public String passThrough(String key) {
    return props.get(key);
  }

  public String cachedOrNull() {
    return cached == null ? null : cached.trim();
  }
}

class NamedConfig extends Config {
  @Override
  public String name() {
    return "named";
  }
}

class NullEntry implements Map.Entry<String, String> {
  public String getKey() {
    return "k";
  }

  public String getValue() {
    return null;
  }

  public String setValue(String v) {
    return null;
  }
}
