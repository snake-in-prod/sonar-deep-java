package parity; // parity

import java.util.List;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;

class Dto {
  private String name;
  private String endDate;

  String getName() {
    return name;
  }

  String getEndDate() {
    return endDate;
  }
}

class Validator {
  void checkDuplicates(String name) {
    if (name.isEmpty()) {
      throw new IllegalArgumentException();
    }
  }

  int parse(String header) {
    return header.length();
  }
}

class DtoUsers {
  private final Validator v = new Validator();

  int a(Dto d) {
    return d.getName() == null ? 0 : d.getName().length();
  }

  int b(Dto d) {
    String n = d.getName();
    return n != null ? n.length() : 0;
  }

  boolean c(Dto d) {
    return d.getEndDate() != null && d.getName() != null;
  }

  void d(Dto d) {
    v.checkDuplicates(d.getName()); // Noncompliant
  }

  long e(List<Dto> ds) {
    return ds.stream().filter(x -> x.getName().startsWith("a")).count(); // Noncompliant
  }

  boolean f(Dto d) {
    return Objects.requireNonNull(d.getEndDate()).contains("-"); // Noncompliant
  }

  int g(Dto d) {
    return d.getEndDate() == null ? 0 : 1;
  }
}

class Headers {
  private final Validator v = new Validator();

  int a(HttpServletRequest r) {
    String h = r.getHeader("A");
    return h == null ? 0 : h.length();
  }

  int b(HttpServletRequest r) {
    return r.getHeader("B") != null ? 1 : 0;
  }

  int c(HttpServletRequest r) {
    return v.parse(r.getHeader("C")); // Noncompliant
  }
}
