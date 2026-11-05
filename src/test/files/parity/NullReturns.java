package parity; // parity

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class Splitter {
  static List<String> split(String s) {
    if (s.isEmpty()) {
      return null;
    }
    List<String> out = new ArrayList<>();
    out.add(s);
    return out;
  }
}

class Waiter {
  String await(Future<String> f) throws Exception {
    return f.get(5, TimeUnit.SECONDS).trim(); // Noncompliant
  }

  boolean has(String csv, String item) {
    return Splitter.split(csv).contains(item); // Noncompliant
  }
}
