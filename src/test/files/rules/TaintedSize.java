package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Sizes {
  byte[] alloc(HttpServletRequest request) {
    int n = Integer.parseInt(request.getParameter("n"));
    byte[] b = new byte[n]; // Noncompliant
    int m = Integer.parseInt(request.getParameter("m"));
    if (m < 100) {
      return new byte[m];
    }
    return b;
  }
}
