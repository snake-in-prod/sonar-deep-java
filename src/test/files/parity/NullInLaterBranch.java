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
}
