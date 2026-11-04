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

  int lostOnException(String name) throws IOException {
    InputStream in = open(name); // Noncompliant
    int b = in.read();
    in.close();
    return b;
  }

  int closedInFinally(String name) throws IOException {
    InputStream in = open(name);
    try {
      return in.read();
    } finally {
      in.close();
    }
  }

  void dropped(String name) throws IOException {
    open(name); // Noncompliant
  }

  int gzip(InputStream raw) throws IOException {
    java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(raw); // Noncompliant
    int b = gz.read();
    gz.close();
    return b;
  }

  int gzipTry(InputStream raw) throws IOException {
    try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(raw)) {
      return gz.read();
    }
  }

  java.nio.channels.FileChannel channel(java.io.File f) throws IOException {
    java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
    try {
      raf.setLength(10);
      return raf.getChannel();
    } catch (IOException e) {
      raf.close();
      throw e;
    }
  }
}
