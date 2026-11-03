package rules;

import java.util.concurrent.locks.ReentrantLock;

class Helper {
  private final ReentrantLock lock = new ReentrantLock();

  void acquire() {
    lock.lock();
  }

  void work() {
    acquire(); // Noncompliant
    System.out.println("work");
  }

  void good() {
    acquire();
    try {
      System.out.println("work");
    } finally {
      lock.unlock();
    }
  }
}
