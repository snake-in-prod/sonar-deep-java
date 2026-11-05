package parity; // parity

import java.util.List;

class Counter {
  static int count(List<String> items) {
    if (items == null) {
      return 0;
    }
    return items.size();
  }

  static int share(int total, int parts) {
    return total / parts;
  }
}

class Shares {
  int perItem(List<String> items, int total) {
    return Counter.share(total, Counter.count(items)); // Noncompliant
  }
}
