package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Redirects {
  void next(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
    response.sendRedirect(request.getParameter("next")); // Noncompliant
  }
}
