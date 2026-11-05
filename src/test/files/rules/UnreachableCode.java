package rules; // parity

import java.util.concurrent.Callable;

class Retries {
  private static final int ATTEMPTS = 3;

  String fetch(Callable<String> call) throws Exception {
    for (int i = 0; i < ATTEMPTS; i++) {
      try {
        return call.call();
      } catch (RuntimeException e) {
        if (i == ATTEMPTS - 1) {
          throw e;
        }
        Thread.sleep(100);
      }
    }
    return null; // Noncompliant
  }

  String withBreak(Callable<String> call) throws Exception {
    for (int i = 0; i < 3; i++) {
      try {
        return call.call();
      } catch (RuntimeException e) {
        if (i == 2) {
          break;
        }
      }
    }
    return null;
  }

  String swallowed(Callable<String> call) {
    for (int i = 0; i < 3; i++) {
      try {
        return call.call();
      } catch (Exception e) {
        System.out.println(i);
      }
    }
    return null;
  }

  private void fail(String m) {
    throw new IllegalStateException(m);
  }

  int afterThrow(int x) {
    if (x < 0) {
      fail("negative");
      System.out.println("unreachable"); // Noncompliant
    }
    return x;
  }
}
