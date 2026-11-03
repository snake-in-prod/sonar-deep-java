package nullness;

import java.util.Map;
import java.util.Objects;

class Client {

  int port(Config c) {
    return c.host().indexOf(':'); // Noncompliant {{"Config.host()" can return null (Config.java:14 via "Map.get()"); check the result before dereferencing it.}}
  }

  int viaLocal(Config c) {
    String h = c.host();
    return h.length(); // Noncompliant
  }

  int transitive(Config c) {
    return c.findViaHelper("k").length(); // Noncompliant {{"Config.findViaHelper()" can return null (Config.java:33 via "Config.find()" at Config.java:27); check the result before dereferencing it.}}
  }

  int fromCatch(Config c) {
    return c.fromCatch("x").length(); // Noncompliant
  }

  int library(Map<String, String> m) {
    return m.get("k").length(); // Noncompliant {{"Map.get()" can return null (library model); check the result before dereferencing it.}}
  }

  int property() {
    return System.getProperty("app.home").length(); // Noncompliant
  }

  int standardProperty(Config c) {
    int n = System.getProperty("os.name").length();
    n += c.host() == null ? 0 : c.host().length();
    return n;
  }

  int compliant(Config c, Map<String, String> m) {
    int n = c.hostOrDefault().length();
    n += c.checked("k").length();
    n += c.guardedGet("k").length();
    n += Config.never("x").length();
    n += System.getProperty("x", "default").length();
    String h = c.host();
    if (h != null) {
      n += h.length();
    }
    String h2 = c.host();
    n += Objects.requireNonNull(h2).length();
    return n;
  }

  private static final Map<String, String> TABLE = new java.util.HashMap<>();

  int argumentDependent(Config c) {
    int n = Config.trimOrNull("x").length();
    n += Config.classOf("x").getName().length();
    n += Config.fill(new String[1]).length;
    n += Config.lookup("k").length();
    n += TABLE.get("k").length();
    n += Client.TABLE.get("k").length();
    n += c.copy().hashCode();
    n += c.name().length();
    n += c.failing("x").length();
    n += c.cachedOrNull().length();
    n += c.passThrough("k").length();
    for (Map.Entry<String, String> e : new java.util.HashMap<String, String>().entrySet()) {
      n += e.getValue().length();
    }
    return n;
  }

  int localNull(Config c) {
    return c.localNull(true).length(); // Noncompliant {{"Config.localNull()" can return null (Config.java:99); check the result before dereferencing it.}}
  }

  int filledAtDeclaration(java.util.List<String> words) {
    Map<String, String> fixed = new java.util.HashMap<>(Map.of("a", "1"));
    Map<Boolean, java.util.List<String>> parts = words.stream().collect(java.util.stream.Collectors.partitioningBy(String::isEmpty));
    Map<String, String> empty = new java.util.HashMap<>(16);
    return fixed.get("a").length() + parts.get(true).size()
      + empty.get("a").length(); // Noncompliant
  }

  int recheckedChain(Config c) {
    return c.props().get("k") != null ? c.props().get("k").length() : 0;
  }

  int computedKey(Map<String, String> m, String key) {
    return m.get(key).length() + m.get(key + "x").length();
  }

  int guardedMap(Map<String, String> m) {
    if (m.containsKey("k")) {
      return m.get("k").length();
    }
    int n = 0;
    for (String k : m.keySet()) {
      n += m.get(k).length();
    }
    return n;
  }
}
