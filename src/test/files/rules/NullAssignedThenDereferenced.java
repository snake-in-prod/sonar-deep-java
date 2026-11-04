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

  String afterLoop(String id, boolean a) {
    Folder f = find(id);
    for (int i = 0; i < 3; i++) {
      if (a) {
        f = null;
      }
    }
    System.out.println(id);
    return f.getName(); // Noncompliant
  }

  String nestedIfs(String id, boolean a, boolean b) {
    Folder f = find(id);
    if (a) {
      if (b) {
        f = null;
      }
    }
    return f.getName(); // Noncompliant
  }

  String laterBranch(boolean a, String id) {
    String name = id.trim();
    if (a) {
      name = null;
    }
    System.out.println(id);
    if (id.isEmpty()) {
      return name.concat("x");
    }
    return "";
  }

  String reassignedInBranch(boolean a, String id) {
    String name = id.trim();
    if (a) {
      name = null;
    }
    if (name == null) {
      name = "n";
    }
    return name.trim();
  }
}
