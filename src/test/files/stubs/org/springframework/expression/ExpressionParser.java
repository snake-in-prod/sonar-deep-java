package org.springframework.expression;

public interface ExpressionParser {
  Object parseExpression(String expression);
}
