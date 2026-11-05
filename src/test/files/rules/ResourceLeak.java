package rules; // parity

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

class Archives {
  int first(File f) throws IOException {
    try (InputStream in = new GZIPInputStream(new FileInputStream(f))) { // Noncompliant
      return in.read();
    }
  }

  int safe(File f) throws IOException {
    try (InputStream raw = new FileInputStream(f); InputStream in = new GZIPInputStream(raw)) {
      return in.read();
    }
  }

  int buffered(File f) throws IOException {
    try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(f))) {
      return in.read();
    }
  }
}
