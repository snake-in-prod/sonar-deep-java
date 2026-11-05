package parity; // parity

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class CachedFuture implements Future<String> {
  private volatile String value;

  public boolean cancel(boolean b) {
    return false;
  }

  public boolean isCancelled() {
    return false;
  }

  public boolean isDone() {
    return value != null;
  }

  public String get() {
    return value == null ? "" : value;
  }

  public String get(long timeout, TimeUnit unit) {
    if (timeout <= 0) {
      return null;
    }
    return get();
  }
}

class Waits {
  String await(CachedFuture f) {
    return f.get(1, TimeUnit.SECONDS).trim(); // Noncompliant
  }
}
