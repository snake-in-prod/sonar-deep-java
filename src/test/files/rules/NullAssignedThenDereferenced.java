package rules;

class Folder {
  boolean isActive() {
    return true;
  }

  String getName() {
    return "f";
  }
}

class Folders {
  Folder find(String id) {
    return new Folder();
  }

  String name(String id) {
    Folder f = find(id);
    if (f == null || !f.isActive()) {
      f = null;
    }
    return f.getName(); // Noncompliant
  }

  String checked(String id) {
    Folder f = find(id);
    if (!f.isActive()) {
      f = null;
    }
    if (f == null) {
      return "";
    }
    return f.getName();
  }

  String leaves(String id) {
    Folder f = find(id);
    if (!f.isActive()) {
      f = null;
      return "none";
    }
    return f.getName();
  }

  String fromCatch(String id) {
    Folder f;
    try {
      f = find(id);
    } catch (RuntimeException e) {
      f = null;
    }
    return f.getName(); // Noncompliant
  }
}
