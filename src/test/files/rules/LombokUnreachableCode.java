package rules; // parity

@lombok.Data // Noncompliant
class Account {
  @lombok.NonNull
  private String owner;
  private String note;
}

@lombok.Data
class Plain {
  private String note;
}

class Service {
  void run(@lombok.NonNull String name) { // Noncompliant
    System.out.println(name);
  }

  void handWritten(@org.example.lib.NonNull String name) {
    if (name == null) {
      throw new IllegalArgumentException("name");
    }
    System.out.println(name);
  }
}
