package parity; // parity

interface Listener {
  void onChange();
}

class Registry {
  private Listener listener;

  synchronized void register() {
    listener.onChange();
  }

  synchronized void lookup() {
    System.out.println("lookup");
  }
}

class Cache implements Listener {
  private Registry registry;

  @Override
  public synchronized void onChange() {
    System.out.println("changed");
  }

  synchronized void refresh() {
    reload(); // Noncompliant
  }

  private void reload() {
    registry.lookup();
  }
}
