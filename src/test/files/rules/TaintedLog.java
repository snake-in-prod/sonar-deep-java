package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Audit {
  private final org.slf4j.Logger log;

  Audit(org.slf4j.Logger log) {
    this.log = log;
  }

  void login(HttpServletRequest request) {
    String user = request.getParameter("user");
    log.info("login " + user); // Noncompliant
    log.info("login " + user.replaceAll("[\r\n]", ""));
    log.info("login {}", user);
  }
}
