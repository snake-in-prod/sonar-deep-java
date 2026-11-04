package rules;

import java.util.List;

class Averages {
  int average(List<Integer> items, int total) {
    return total / items.size(); // Noncompliant
  }

  int checked(List<Integer> items, int total) {
    if (items.isEmpty()) {
      return 0;
    }
    return total / items.size();
  }

  int viaLocal(List<Integer> items, int total) {
    int n = items.size();
    return n > 0 ? total / n : 0;
  }

  double floating(List<Integer> items, double total) {
    return total / items.size();
  }
}
