package parity; // parity

class Names {
  String laterBranch(boolean a, String id) {
    String name = id.trim();
    if (a) {
      name = null;
    }
    System.out.println(id);
    if (id.isEmpty()) {
      return name.concat("x"); // Noncompliant
    }
    return "";
  }

  String twoBranches(boolean a, boolean b, String id) {
    String s = null;
    if (a) {
      s = id;
    }
    if (b) {
      s = id.trim();
    }
    System.out.println(id);
    return s.concat("x"); // Noncompliant
  }
}
