package parity; // parity

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class CookieUtil {
  static void add(HttpServletResponse response, String name, String value) {
    Cookie c = new Cookie(name, value); // Noncompliant
    response.addCookie(c);
  }
}

class Preferences {
  void save(HttpServletRequest request, HttpServletResponse response) {
    CookieUtil.add(response, "lang", String.valueOf(request.getParameter("lang"))); // Noncompliant
  }
}
