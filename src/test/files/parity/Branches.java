package parity; // parity

class Event {
  private String start;

  String getStart() {
    return start;
  }

  void setStart(String s) {
    start = s;
  }
}

class Merger {
  void merge(Event target, Event source) {
    if (target.getStart() != null) { // Noncompliant
      target.setStart(source.getStart());
    }
  }

  String clean(String s, boolean dashes) {
    if (dashes) {
      return s.trim().replaceAll("-+", "").toLowerCase();
    } else { // Noncompliant
      return s.trim().replaceAll("_+", "").toLowerCase();
    }
  }

  String kind(String s) {
    if (s.matches("[0-9]+")) {
      return s.trim().replaceAll("^0+", "").toLowerCase();
    } else if (s.length() > 3) { // Noncompliant
      return s.trim().replaceAll("^x+", "").toLowerCase();
    }
    return s;
  }

  void counts(java.util.Map<String, Integer> stats, int complianceCount, int nonComplianceCount) {
    stats.put("compliance", complianceCount);
    stats.put("nonCompliance", complianceCount); // Noncompliant
  }
}
