package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Files {
  private static final java.util.Set<String> ALLOWED = java.util.Set.of("a.txt");

  java.io.File open(HttpServletRequest request) {
    return new java.io.File("/data", request.getParameter("name")); // Noncompliant
  }

  java.io.File checked(HttpServletRequest request) {
    String n = request.getParameter("name");
    if (ALLOWED.contains(n)) {
      return new java.io.File("/data", n);
    }
    return null;
  }
}

class Storage {
  @org.springframework.beans.factory.annotation.Value("${storage.dir}")
  private String dir;

  java.io.File open() {
    return new java.io.File(dir); // Noncompliant
  }

  String env() throws java.io.IOException {
    return java.nio.file.Files.readString(java.nio.file.Path.of(System.getenv("DATA_FILE"))); // Noncompliant
  }
}

@org.springframework.boot.context.properties.ConfigurationProperties("storage")
class StorageProperties {
  private String root;

  String getRoot() {
    return root;
  }
}

class UsesProperties {
  boolean create(StorageProperties p) throws java.io.IOException {
    java.io.File f = new java.io.File(p.getRoot()); // Noncompliant
    return f.createNewFile(); // Noncompliant
  }
}

