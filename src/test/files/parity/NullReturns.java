package parity; // parity

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.SerializationUtils;
import reactor.core.publisher.Mono;

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
    return f.get(5, TimeUnit.SECONDS).trim(); // library model: not a reference class
  }

  // annotated nullable in the library: NullableDereference in parity mode
  String reactive(Mono<String> m) {
    return m.block().trim();
  }

  int copy(ArrayList<String> items) {
    return SerializationUtils.clone(items).size(); // library model: not a reference class
  }

  // declared nullable: NullableDereference in parity mode
  int annotated(Reply r) {
    return r.getContent().size();
  }

  boolean has(String csv, String item) {
    return Splitter.split(csv).contains(item); // Noncompliant
  }
}
