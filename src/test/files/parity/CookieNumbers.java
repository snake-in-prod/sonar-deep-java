package parity; // parity

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Login {
  void remember(HttpServletRequest request, HttpServletResponse response) {
    long loginTime = Long.parseLong(request.getParameter("t"));
    response.addCookie(new Cookie("login", "" + loginTime)); // Noncompliant
  }
}

class Tokens {
  void setCookie(HttpServletResponse response, String value) {
    response.addCookie(new Cookie("t", value)); // Noncompliant
  }

  void login(HttpServletRequest request, HttpServletResponse response) {
    long loginTime = Long.parseLong(request.getParameter("t"));
    setCookie(response, String.valueOf(loginTime)); // Noncompliant
  }
}
