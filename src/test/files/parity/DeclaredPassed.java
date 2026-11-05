package parity; // parity

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class Payload {
  @javax.annotation.Nullable
  private String data;

  public String getData() {
    return data;
  }
}

interface HeaderMap {
  @javax.annotation.Nullable
  List<String> get(String key);
}

class DeclaredPassed {
  int process(String s) {
    return s.length();
  }

  int passed(Payload p) {
    return process(p.getData()); // Noncompliant
  }

  List<Integer> inLambda(List<Payload> ps) {
    return ps.stream().map(p -> process(p.getData())).collect(Collectors.toList()); // Noncompliant
  }

  String secondCall(HeaderMap h, String k) {
    if (h.get(k) != null) {
      return h.get(k).get(0); // Noncompliant
    }
    return null;
  }

  long filtered(List<Payload> ps) {
    return ps.stream().filter(p -> p.getData().isEmpty()).count(); // Noncompliant
  }

  int checked(Payload p) {
    String d = p.getData();
    return d == null ? 0 : process(d);
  }
}
