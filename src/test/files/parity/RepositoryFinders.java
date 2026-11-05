package parity; // parity

import java.util.Optional;

interface UserRepository {
  User findByName(String name);
}

interface Settings {
  String lookup(String key);

  String section(String key);
}

class User {
  String id() {
    return "u";
  }
}

class Versioned<T> {
  private T version;

  T getVersion() {
    return version;
  }
}

class Accounts {
  String a(UserRepository r) {
    User u = r.findByName("a");
    return u == null ? null : u.id();
  }

  String b(UserRepository r) {
    User u = r.findByName("b");
    if (u != null) {
      return u.id();
    }
    return null;
  }

  boolean a2(UserRepository r) {
    return r.findByName("a2") != null;
  }

  boolean a3(UserRepository r) {
    return r.findByName("a3") == null;
  }

  String c(UserRepository r) {
    return r.findByName("c").id(); // Noncompliant
  }

  int d(Versioned<String> v) {
    return v.getVersion() == null ? 0 : 1;
  }

  int e(Versioned<String> v) {
    String s = v.getVersion();
    return s != null ? s.length() : 0;
  }

  int d2(Versioned<String> v) {
    return v.getVersion() != null ? 2 : 0;
  }

  int d3(Versioned<String> v) {
    return v.getVersion() == null ? 3 : 0;
  }

  int f(Versioned<String> v) {
    return v.getVersion().length(); // Noncompliant
  }

  // tests check it 4 times, main code calls it once: test-dominated statistics are not used
  int g(Settings s) {
    return s.lookup("g").length();
  }

  // main code has 5 sites (2 checked), tests check 4 more: 6 of 9 counting tests
  int h(Settings s) {
    String v = s.section("h");
    return v == null ? 0 : v.length();
  }

  boolean h2(Settings s) {
    return s.section("h2") != null;
  }

  int k(Settings s) {
    return s.section("k").length(); // Noncompliant
  }

  int i(Settings s) {
    return s.section("i").length(); // Noncompliant
  }

  int j(Settings s) {
    return s.section("j").length(); // Noncompliant
  }
}
