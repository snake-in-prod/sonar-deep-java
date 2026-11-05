package parity; // parity

class Engine {
  private Panel panel;

  synchronized void update() {
    panel.refresh(); // Noncompliant
  }

  synchronized int state() {
    return 1;
  }
}

class Panel {
  private Engine engine;

  synchronized void refresh() {
    System.out.println("refresh");
  }

  synchronized void redraw() {
    engine.state(); // Noncompliant
  }
}
