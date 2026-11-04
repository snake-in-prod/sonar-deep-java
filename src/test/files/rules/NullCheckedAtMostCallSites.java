package rules;

class Repo {
  private final java.util.Map<String, String> data = new java.util.HashMap<>();

  String find(String id) {
    return data.get(id);
  }
}

class Callers {
  private final Repo repo = new Repo();

  int a() {
    String s = repo.find("a");
    return s == null ? 0 : s.length();
  }

  int b() {
    String s = repo.find("b");
    if (s != null) {
      return s.length();
    }
    return 0;
  }

  int c() {
    return java.util.Objects.requireNonNullElse(repo.find("c"), "").length();
  }

  int d() {
    return repo.find("d") != null ? 1 : 0;
  }

  int e() {
    return repo.find("e").length(); // Noncompliant
  }
}
