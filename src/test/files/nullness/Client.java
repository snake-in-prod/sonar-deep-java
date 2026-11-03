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
    return System.getProperty("user.dir").length(); // Noncompliant
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
