package rules; // parity

class Orders {
  private String note;

  String describe(String id) {
    if (id == null) {
      throw new IllegalArgumentException("bad id " + id.trim()); // Noncompliant
    }
    return id;
  }

  boolean empty(String s) {
    return s == null && s.isEmpty(); // Noncompliant
  }

  boolean blank(String s) {
    return s != null || s.isBlank(); // Noncompliant
  }

  int size(String s) {
    return s == null ? s.length() : 0; // Noncompliant
  }

  int elseBranch(String s) {
    if (s != null) {
      return 1;
    } else {
      return s.length(); // Noncompliant
    }
  }

  String assigned(String s) {
    if (s == null) {
      s = "x";
      return s.trim();
    }
    return s;
  }

  String fieldInit() {
    if (note == null) {
      load();
      return note.trim();
    }
    return note;
  }

  private void load() {
    note = "n";
  }

  boolean fine(String s) {
    return s != null && s.isEmpty();
  }
}
