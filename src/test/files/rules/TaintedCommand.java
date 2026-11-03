package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Ping {
  void ping(HttpServletRequest request) throws java.io.IOException {
    Runtime.getRuntime().exec("ping " + request.getParameter("host")); // Noncompliant
    Runtime.getRuntime().exec("ping localhost");
  }
}
