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
}
