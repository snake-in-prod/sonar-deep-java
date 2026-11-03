package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Ldap {
  Object find(HttpServletRequest request, javax.naming.directory.DirContext ctx) throws javax.naming.NamingException {
    String user = request.getParameter("user");
    return ctx.search("ou=people", "(uid=" + user + ")", new javax.naming.directory.SearchControls()); // Noncompliant
  }
}
