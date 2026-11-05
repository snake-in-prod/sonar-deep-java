package parity; // parity

import java.util.List;

class Sizes {
  static int count(List<String> items) {
    if (items == null) {
      return 0;
    }
    return items.size();
  }
}

class Stats {
  int average(List<String> items, int total) {
    return total / Sizes.count(items); // Noncompliant
  }
}
