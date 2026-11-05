package parity; // parity

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

interface Entry {
  Entry getX();

  String getY();

  String getName();
}

class LambdaStatistics {
  int a(Entry e) {
    return e.getX() == null ? 0 : 1;
  }

  int b(Entry e) {
    return e.getX() != null ? 1 : 0;
  }

  int c(Entry e) {
    Entry x = e.getX();
    return x == null ? 0 : 2;
  }

  Map<String, String> toMap(List<Entry> es) {
    return es.stream().filter(e -> e.getX() != null).collect(Collectors.toMap(e -> e.getX().getY(), Entry::getName)); // Noncompliant
  }

  int d(Entry e) {
    return e.getX() != null ? 3 : 0;
  }
}
