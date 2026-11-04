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

class PageRequest {
  private int pageSize;

  int getPageSize() {
    return pageSize;
  }
}

class Pager {
  int pages(PageRequest request, int total) {
    return total / request.getPageSize(); // Noncompliant
  }

  int counted(String s) {
    if (s.isEmpty()) {
      return 0;
    }
    return s.length();
  }

  int ratio(String s, int total) {
    return total / counted(s); // Noncompliant
  }

  int ratioLocal(String s, int total) {
    int c = counted(s);
    return total / c; // Noncompliant
  }

  int seven() {
    return 7;
  }

  int nonZero(int total) {
    return total / seven();
  }
}

class Pages {
  static int total(int count, int size) {
    return count / size;
  }

  int pages(PageRequest request, int count) {
    return total(count, request.getPageSize()); // Noncompliant
  }

  int fixed(int count) {
    return total(count, 10);
  }
}
