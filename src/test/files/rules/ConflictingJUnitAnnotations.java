package rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class Conflicts {
  @Test
  @RepeatedTest(2)
  void twice() { // Noncompliant
  }

  @Test
  @BeforeEach
  void setUp() { // Noncompliant
  }

  @Test
  void ok() {
  }
}

class Mixed4And5 { // Noncompliant
  @org.junit.Test
  public void old() {
  }

  @Test
  void jupiter() {
  }
}
