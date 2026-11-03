package rules;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

class Closer {
  static void closeQuietly(Closeable c) {
    try {
      c.close();
    } catch (IOException e) {
      // ignore
    }
  }

  void twice(InputStream in) throws IOException {
    closeQuietly(in);
    in.close(); // Noncompliant
  }

  int afterClose(InputStream in) throws IOException {
    closeQuietly(in);
    return in.read(); 
  }
}
