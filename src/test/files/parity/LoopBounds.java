package parity; // parity

import java.util.Map;
import javax.servlet.http.HttpServletRequest;

class LoopBounds {
  void parsedInCondition(Map<String, String> cfg) {
    for (int i = 0; i < Integer.parseInt(cfg.get("n")); i++) { // Noncompliant
      System.out.println(i);
    }
  }

  void countDown(Map<String, String> cfg) {
    int n = Integer.parseInt(cfg.get("n"));
    while (n > 0) { // Noncompliant
      n--;
    }
  }

  void downFrom(HttpServletRequest request) {
    int n = Integer.parseInt(request.getParameter("n"));
    for (int i = n; i > 0; i--) { // Noncompliant
      System.out.println(i);
    }
  }

  void downFromConfig(Map<String, String> cfg) {
    int n = Integer.parseInt(cfg.get("n"));
    for (int i = n; i > 0; i--) { // Noncompliant
      System.out.println(i);
    }
  }

  void downFromNoPrint(HttpServletRequest request) {
    int n = Integer.parseInt(request.getParameter("n"));
    for (int i = n; i > 0; i--) { // Noncompliant
      n++;
    }
  }

  void retries(Map<String, String> cfg) {
    int left = Integer.parseInt(cfg.get("retries"));
    do {
      System.out.println(left);
    } while (--left > 0); // Noncompliant
  }

  boolean notALoop(Map<String, String> cfg) {
    int v = Integer.parseInt(cfg.get("v"));
    return v > 255;
  }

  void checkInsideLoop(Map<String, String> cfg) {
    int max = Integer.parseInt(cfg.get("max"));
    for (int i = 0; i < 10; i++) {
      if (max > 255) {
        System.out.println(i);
      }
    }
  }
}
