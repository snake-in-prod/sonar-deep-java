package rules;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

class Streams {
  InputStream open(String name) throws IOException {
    return new FileInputStream(name);
  }

  int leak(String name) throws IOException {
    InputStream in = open(name); // Noncompliant
    return in.read();
  }

  int closed(String name) throws IOException {
    try (InputStream in = open(name)) {
      return in.read();
    }
  }

  InputStream handedOn(String name) throws IOException {
    InputStream in = open(name);
    return in;
  }

  void dropped(String name) throws IOException {
    open(name); // Noncompliant
  }
}
