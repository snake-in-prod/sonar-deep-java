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

  String exitsByReturn(Map<String, String> cfg, String[] items) {
    for (String item : items) {
      int v = Integer.parseInt(cfg.get(item));
      if (v > 14) { // Noncompliant
        return "error";
      }
    }
    return "ok";
  }

  void exitsByThrow(Map<String, String> cfg, String[] items) {
    for (String item : items) {
      int v = Integer.parseInt(cfg.get(item));
      if (v < 0 || v > 255) { // Noncompliant
        throw new IllegalArgumentException(item);
      }
    }
  }

  boolean exitsByBreak(Map<String, String> cfg, String[] items) {
    boolean flag = false;
    for (String item : items) {
      int v = Integer.parseInt(cfg.get(item));
      if (v >= 100) { // Noncompliant
        flag = true;
        break;
      }
    }
    return flag;
  }

  void repeat(int times) {
    for (int i = 0; i < times; i++) { // Noncompliant
      System.out.println(i);
    }
  }

  void passedToLoopingHelper(Map<String, String> cfg) {
    repeat(Integer.parseInt(cfg.get("times"))); // Noncompliant
  }

  void lineLength(java.io.BufferedReader r) throws java.io.IOException {
    String line = r.readLine();
    for (int i = 0; i < line.length(); i++) { // Noncompliant
      System.out.println(line.charAt(i));
    }
  }
}
