package rules;

import java.util.concurrent.locks.StampedLock;

class Stamped {
  private final StampedLock lock = new StampedLock();
  private int v;

  void update() {
    long s = lock.writeLock();
    try {
      v++;
    } finally {
      lock.unlockWrite(s);
    }
  }

  void outer() {
    long s = lock.writeLock();
    try {
      update(); // Noncompliant
    } finally {
      lock.unlockWrite(s);
    }
  }
}
