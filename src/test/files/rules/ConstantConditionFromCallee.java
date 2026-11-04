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
}
