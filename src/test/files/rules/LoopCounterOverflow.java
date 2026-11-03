package rules;

class Counters {
  void bytes() {
    for (byte b = 0; b < 200; b++) { // Noncompliant
      System.out.println(b);
    }
    for (byte b = 0; b < 100; b++) {
      System.out.println(b);
    }
    for (int i = 0; i <= Integer.MAX_VALUE; i++) { // Noncompliant
      System.out.println(i);
    }
  }
}
