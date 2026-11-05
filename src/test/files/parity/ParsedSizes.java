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

  boolean range(Map<String, String> cfg) {
    int lo = Integer.parseInt(cfg.get("lo"));
    int hi = Integer.parseInt(cfg.get("hi"));
    if (hi < lo) { // Noncompliant
      return false;
    }
    return true;
  }

  boolean negative(Map<String, String> cfg) {
    int n = Integer.parseInt(cfg.get("n"));
    if (n < 0) { // Noncompliant
      return false;
    }
    return true;
  }

  void purge(long period) {
    for (long t = 0; t < period; t++) { // Noncompliant
      System.out.println(t);
    }
  }

  void retention(Map<String, String> cfg) {
    purge(Long.parseLong(cfg.get("period"))); // Noncompliant
  }
}
