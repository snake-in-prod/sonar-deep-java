package parity; // parity

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

class CatchNullChecks {
  private Writer out;

  void open(String p) {
    FileOutputStream a = null;
    OutputStreamWriter b = null;
    BufferedWriter c = null;
    try {
      a = new FileOutputStream(p);
      b = new OutputStreamWriter(a);
      c = new BufferedWriter(b);
      out = c;
    } catch (IOException e) {
      if (c != null) { // Noncompliant
        close(c);
      }
      if (b != null) { // Noncompliant
        close(b);
      }
      if (a != null) { // Noncompliant
        close(a);
      }
    }
  }

  void written(String p) {
    BufferedWriter c = null;
    try {
      c = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(p)));
      c.write("x");
    } catch (IOException e) {
      if (c != null) {
        close(c);
      }
    }
  }

  void anyException(String p) {
    FileOutputStream a = null;
    try {
      a = new FileOutputStream(p);
    } catch (Exception e) {
      if (a != null) {
        close(a);
      }
    }
  }

  private static void close(java.io.Closeable c) {
    try {
      c.close();
    } catch (IOException ignored) {
      // closing
    }
  }
}
