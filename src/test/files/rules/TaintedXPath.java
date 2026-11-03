package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Query {
  Object find(HttpServletRequest request, javax.xml.xpath.XPath xp, Object doc) throws javax.xml.xpath.XPathExpressionException {
    String name = request.getParameter("name");
    return xp.evaluate("//u[@n='" + name + "']", doc); // Noncompliant
  }
}
