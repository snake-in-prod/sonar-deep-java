package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Access {
  boolean admin(HttpServletRequest request) {
    javax.servlet.http.Cookie[] cookies = request.getCookies();
    return cookies[0].getValue().equals("admin"); // Noncompliant
  }

  boolean role(String token) {
    return com.auth0.jwt.JWT.decode(token).getClaim("role").asString().equals("admin"); // Noncompliant
  }
}
