package rules; // parity

import java.util.Map;
import javax.servlet.http.HttpServletRequest;

class Batches {
  void run(HttpServletRequest request) {
    int n = Integer.parseInt(request.getParameter("n"));
    for (int i = 0; i < n; i++) { // Noncompliant
      System.out.println(i);
    }
  }

  void purge(long period) {
    for (long t = 0; t < period; t++) { // Noncompliant
      System.out.println(t);
    }
  }

  void retention(Map<String, String> cfg) {
    purge(Long.parseLong(cfg.get("period"))); // Noncompliant
  }

  void bounded(HttpServletRequest request) {
    int n = Math.min(100, Integer.parseInt(request.getParameter("n")));
    for (int i = 0; i < 10; i++) {
      System.out.println(i + n);
    }
  }

  int index(HttpServletRequest request, int[] a) {
    return a[Integer.parseInt(request.getParameter("i"))];
  }
}
