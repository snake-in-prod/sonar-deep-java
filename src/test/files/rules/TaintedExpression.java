package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Expressions {
  Object eval(HttpServletRequest request, org.springframework.expression.ExpressionParser parser) {
    return parser.parseExpression(request.getParameter("expr")); // Noncompliant
  }
}
