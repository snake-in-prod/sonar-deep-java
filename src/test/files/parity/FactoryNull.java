package parity; // parity

class Shapes {
  interface Shape {
    int area();
  }

  static Shape create(String kind) {
    switch (kind) {
      case "square":
        return () -> 4;
      default:
        return null;
    }
  }

  int use() {
    return create("circle").area(); // Noncompliant
  }
}
