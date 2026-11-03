package rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

class AfterThrowTest {
  @Test
  void t() {
    int count = 1;
    fail("not implemented");
    assertEquals(1, count); // Noncompliant
  }
}
