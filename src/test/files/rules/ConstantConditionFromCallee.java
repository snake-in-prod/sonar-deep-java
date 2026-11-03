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
}
