package rules;

class Auth {
  boolean login(String user, String pw) {
    return pw.length() > 3;
  }

  void connect(String user) {
    boolean ok = false;
    while (!ok) { // Noncompliant
      ok = login(user, read());
    }
  }

  void limited(String user) {
    boolean ok = false;
    int attempts = 0;
    while (!ok && attempts < 3) {
      ok = login(user, read());
      attempts++;
    }
  }

  String read() {
    return "x";
  }
}
