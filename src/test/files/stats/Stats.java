package stats;

import java.util.Objects;

class Repo {
  String find(int id) {
    if (id < 0) {
      return null;
    }
    return "x" + id;
  }

  String rarelyChecked(int id) {
    if (id < 0) {
      return null;
    }
    return "y" + id;
  }
}

class Users {
  int a(Repo r) {
    String s = r.find(1);
    return s == null ? 0 : s.length();
  }

  int b(Repo r) {
    String s = r.find(2);
    if (s != null) {
      return s.length();
    }
    return 0;
  }

  int c(Repo r) {
    return Objects.requireNonNullElse(r.find(3), "").length();
  }

  int d(Repo r) {
    return r.find(4) != null ? 1 : 0;
  }

  int e(Repo r) {
    return r.find(5).length(); // Noncompliant {{"Repo.find()" can return null (Stats.java:8; null-checked at 4 of 5 call sites); check the result before dereferencing it.}}
  }

  // checked at 1 of 5 call sites: the authors treat it as non-null here
  int f(Repo r) {
    String s = r.rarelyChecked(1);
    int n = s == null ? 0 : 1;
    return n + r.rarelyChecked(2).length() + r.rarelyChecked(3).length() + r.rarelyChecked(4).length() + r.rarelyChecked(5).length();
  }
}
