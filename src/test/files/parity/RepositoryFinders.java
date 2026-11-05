package parity; // parity

import java.util.Optional;

interface UserRepository {
  User findByName(String name);
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

  int f(Versioned<String> v) {
    return v.getVersion().length(); // Noncompliant
  }
}
