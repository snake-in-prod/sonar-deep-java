package parity; // parity

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

class LocalWrappers {
  int leak(InputStream in) throws IOException {
    GZIPInputStream g = new GZIPInputStream(in); // Noncompliant
    return g.read();
  }

  int closed(InputStream in) throws IOException {
    GZIPInputStream g = new GZIPInputStream(in);
    try {
      return g.read();
    } finally {
      g.close();
    }
  }

  InputStream returned(InputStream in) throws IOException {
    GZIPInputStream g = new GZIPInputStream(in);
    return g;
  }

  int managed(InputStream in) throws IOException {
    try (GZIPInputStream g = new GZIPInputStream(in)) {
      return g.read();
    }
  }
}
