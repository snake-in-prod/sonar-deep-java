package io.github.snakeinprod.deepjava.prepass;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.snakeinprod.deepjava.prepass.ClassFacts.MethodFacts;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassFactsTest {

  @Test
  void cache_format_round_trips() throws Exception {
    ClassFacts facts = new ClassFacts("a/B", null, List.of("a/I"), "B.java", Map.of(
      "m()Ljava/lang/String;", new MethodFacts(true, Map.of(12, Set.of("!a/C#fail()V"), 14, Set.of()), false, true,
        Map.of("!a/C#n()Ljava/lang/Object;", 13), Set.of("n", "containsKey"),
        Map.of("a/C#n()Ljava/lang/Object;", 3), Map.of("a/C#n()Ljava/lang/Object;", 2)),
      "fail()V", MethodFacts.empty(false, false)));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    facts.write(new DataOutputStream(bytes));
    assertThat(ClassFacts.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())))).isEqualTo(facts);
  }
}
