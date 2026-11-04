package parity; // parity

class Unclosed {
  int first(java.io.InputStream raw) throws java.io.IOException {
    java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(raw); // Noncompliant
    return gz.read();
  }
}
