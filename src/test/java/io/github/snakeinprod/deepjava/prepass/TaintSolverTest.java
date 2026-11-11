package io.github.snakeinprod.deepjava.prepass;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.snakeinprod.deepjava.prepass.ClassFacts.MethodFacts;
import io.github.snakeinprod.deepjava.prepass.ClassFacts.Sink;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaintSolverTest {

  /** Sink flows sit in hash sets: hashing one must not walk the facts of its class (that dominated the taint pass). */
  @Test
  void sink_flow_hash_does_not_walk_class_facts() {
    Map<String, MethodFacts> methods = new AbstractMap<>() {
      @Override
      public Set<Entry<String, MethodFacts>> entrySet() {
        return Set.of();
      }

      @Override
      public int hashCode() {
        throw new AssertionError("hashed the class facts");
      }
    };
    ClassFacts c = new ClassFacts("com/acme/Dao", "java/lang/Object", List.of(), null, 0, Map.of(), Map.of(), methods);
    Sink sink = new Sink("SQL", 12, Set.of("P0"), "java/sql/Statement#execute");
    TaintSolver.SinkFlow a = new TaintSolver.SinkFlow(0, sink, c, null);
    TaintSolver.SinkFlow b = new TaintSolver.SinkFlow(0, sink, c, null);
    assertThat(Set.of(a)).contains(b);
    assertThat(a.hashCode()).isEqualTo(b.hashCode()).isNotEqualTo(new TaintSolver.SinkFlow(1, sink, c, null).hashCode());
  }
}
