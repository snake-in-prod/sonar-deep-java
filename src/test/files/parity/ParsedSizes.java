package parity; // parity

import java.util.Map;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Limits {
  int parsed(Map<String, String> cfg) {
    int n = Integer.parseInt(cfg.get("n"));
    return new byte[n].length; // Noncompliant
  }

  boolean check(Map<String, String> cfg) {
    int v = Integer.parseInt(cfg.get("v"));
    if (v > 255) { // Noncompliant
      return false;
    }
    return true;
  }

  void pause(Map<String, String> cfg) throws InterruptedException {
    Thread.sleep(Long.parseLong(cfg.get("ms"))); // Noncompliant
  }
}
