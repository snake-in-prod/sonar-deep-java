package parity; // parity

class Wide {
  static int build(String a, String b, String c, String d, String e, String f, String g, String h, String i, String j) {
    return a.length() + j.length();
  }
}

class WideCaller {
  int call(boolean flag) {
    String last = null;
    if (flag) {
      System.out.println("flag");
    }
    return Wide.build("a", "b", "c", "d", "e", "f", "g", "h", "i", last); // Noncompliant
  }
}
