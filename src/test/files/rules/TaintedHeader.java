package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Headers {
  void echo(HttpServletRequest request, HttpServletResponse response) {
    response.setHeader("X-User", request.getParameter("u")); // Noncompliant
    response.setHeader("X-Static", "v");
  }
}
