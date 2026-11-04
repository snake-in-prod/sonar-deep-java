package parity; // parity

public class PublicPaths {
  public java.io.File open(String name) {
    return new java.io.File("/data", name); // Noncompliant
  }

  java.io.File internal(String name) {
    return new java.io.File(name);
  }

  public java.io.Reader read(java.io.File file) throws java.io.IOException {
    return new java.io.FileReader(file); // Noncompliant
  }
}
