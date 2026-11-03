package rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SetupTest {
  @Test
  void t() {
    List<String> list = new ArrayList<>();
    list.add("a");
    assertEquals(1, list.size());
    list.add("b"); // Noncompliant
  }

  @Test
  void clean() {
    List<String> list = new ArrayList<>();
    assertEquals(0, list.size());
    list.clear();
  }
}
