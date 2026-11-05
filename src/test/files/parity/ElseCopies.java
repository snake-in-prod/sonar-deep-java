package parity; // parity

class ElseCopies {
  boolean accept(int code) {
    boolean result;
    if (code == 200) {
      result = true;
    } else if (code == 404) { // Noncompliant
      result = false;
    } else {
      result = false;
    }
    return result;
  }

  boolean simple(boolean a) {
    boolean result;
    if (a) {
      result = false;
    } else {
      result = false;
    }
    return result;
  }
}
