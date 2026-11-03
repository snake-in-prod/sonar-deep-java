package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Formats {
  String show(HttpServletRequest request) {
    return String.format(request.getParameter("fmt"), 1); // Noncompliant
  }
}
