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

  private java.io.InputStream open(String name) {
    throw new UnsupportedOperationException(name);
  }

  int resource(String name) throws java.io.IOException {
    try (java.io.InputStream in = open(name)) { // Noncompliant
      return in.read();
    }
  }
}
