package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Ping {
  @org.springframework.beans.factory.annotation.Value("${installer.path}")
  private String installer;

  void install() throws java.io.IOException {
    new ProcessBuilder(installer, "--quiet").start(); // Noncompliant
  }

  void fromEnvironment(org.springframework.core.env.Environment env) throws java.io.IOException {
    Runtime.getRuntime().exec(env.getProperty("tool")); // Noncompliant
  }

  void ping(HttpServletRequest request) throws java.io.IOException {
    Runtime.getRuntime().exec("ping " + request.getParameter("host")); // Noncompliant
    Runtime.getRuntime().exec("ping localhost");
  }
}
