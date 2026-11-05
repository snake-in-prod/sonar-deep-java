package parity; // parity

class Labels {
  int length(String s) {
    return s.length();
  }

  int label(boolean b) {
    String name = null;
    if (b) {
      name = "x";
    }
    return length(name); // Noncompliant
  }

  int fine(boolean b) {
    String name = null;
    if (b) {
      name = "x";
    } else {
      name = "y";
    }
    return length(name);
  }
}
