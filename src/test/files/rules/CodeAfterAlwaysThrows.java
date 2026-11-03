package rules;

class Failing {
  private void fail(String m) {
    throw new IllegalStateException(m);
  }

  int run(int x) {
    if (x < 0) {
      fail("negative");
      System.out.println("unreachable"); // Noncompliant
    }
    if (x == 0) {
      fail("zero");
      return -1;
    }
    return x;
  }
}
