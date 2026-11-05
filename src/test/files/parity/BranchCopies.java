package parity; // parity

class BranchCopies {
  boolean ok(String s) {
    return s != null && !s.isEmpty();
  }

  void report(String which) {
    System.out.println(which);
  }

  void validate(String newData, String thisNe, String other) {
    if (!ok(newData)) {
      report(thisNe); // Noncompliant
    } else if (!ok(thisNe)) {
      report(thisNe);
    } else if (!ok(other)) {
      report(other);
    }
  }

  String pick(String anchorType, String nonAnchorType) {
    if (anchorType != null) {
      return nonAnchorType; // Noncompliant
    }
    return "none";
  }

  String same(String anchorType, String nonAnchorType) {
    if (anchorType != null) {
      return anchorType;
    }
    return nonAnchorType;
  }
}
