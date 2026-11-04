package rules;

class Flags {
  private static boolean debug = false;

  static boolean enabled() {
    return false;
  }

  void run() {
    if (enabled()) { // Noncompliant
      System.out.println("on");
    }
    if (debug) { // Noncompliant
      System.out.println("debug");
    }
  }

  void chars(char c) {
    if ('\u0000' <= c && c <= 'z') { // Noncompliant
      System.out.println(c);
    }
    if (c >= 'a') {
      System.out.println(c);
    }
    if (false) { // Noncompliant
      System.out.println("never");
    }
  }

  private static final int RETRIES = 0;

  void never() {
    for (int attempt = 1; attempt <= RETRIES; attempt++) { // Noncompliant
      System.out.println(attempt);
    }
    for (int attempt = 1; attempt <= 3; attempt++) {
      System.out.println(attempt);
    }
  }
}
