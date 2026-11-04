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
