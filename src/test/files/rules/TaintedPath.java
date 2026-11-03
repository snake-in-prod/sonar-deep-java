package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Files {
  private static final java.util.Set<String> ALLOWED = java.util.Set.of("a.txt");

  java.io.File open(HttpServletRequest request) {
    return new java.io.File("/data", request.getParameter("name")); // Noncompliant
  }

  java.io.File checked(HttpServletRequest request) {
    String n = request.getParameter("name");
    if (ALLOWED.contains(n)) {
      return new java.io.File("/data", n);
    }
    return null;
  }
}
