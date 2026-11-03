package rules;

class Mailer {
  void send(String to) {
    System.out.println(to.trim());
  }

  void maybe(String to) {
    if (to != null) {
      System.out.println(to.trim());
    }
  }

  void use() {
    send(null); // Noncompliant
    maybe(null);
    send("x");
  }
}
