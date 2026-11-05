package rules; // parity

import javax.annotation.Nullable;

class Profile {
  @Nullable
  private String nickname;

  @Nullable
  String middleName() {
    return null;
  }

  String nickname() {
    return nickname;
  }

  int length() {
    return nickname.length(); // Noncompliant
  }
}

class Profiles {
  int a(Profile p) {
    return p.middleName().length(); // Noncompliant
  }

  int b(Profile p) {
    String n = p.nickname();
    return n.length(); // Noncompliant
  }

  int checked(Profile p) {
    String m = p.middleName();
    return m == null ? 0 : m.length();
  }

  String fromMap(java.util.Map<String, String> m) {
    return m.get("k").trim();
  }
}
