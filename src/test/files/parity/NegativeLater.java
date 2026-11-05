package parity; // parity

class Cutter {
  static String head(String s, int end) {
    return s.substring(0, end);
  }
}

class NegativeLater {
  String cut(String s, boolean a, boolean b) {
    int at = s.indexOf(':');
    if (a) {
      System.out.println("a");
    } else if (b) {
      System.out.println("b");
    }
    return Cutter.head(s, at); // Noncompliant
  }
}
