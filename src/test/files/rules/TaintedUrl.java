package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Fetch {
  java.io.InputStream fetch(HttpServletRequest request) throws java.io.IOException {
    return new java.net.URL(request.getParameter("u")).openStream(); // Noncompliant
  }
}
