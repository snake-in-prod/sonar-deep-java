package rules;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

class Sizes {
  int records(java.nio.ByteBuffer buf) {
    int count = buf.getShort() & 0xFFFF;
    int sum = 0;
    for (int i = 0; i < count; i++) { // Noncompliant
      sum += i;
    }
    int bounded = Math.min(buf.getInt(), 100);
    return sum + new byte[bounded].length;
  }

  byte[] alloc(HttpServletRequest request) {
    int n = Integer.parseInt(request.getParameter("n"));
    byte[] b = new byte[n]; // Noncompliant
    int m = Integer.parseInt(request.getParameter("m"));
    if (m < 100) {
      return new byte[m];
    }
    return b;
  }

  void fill(int n) {
    for (int i = 0; i < n; i++) {
      System.out.println(i);
    }
  }

  void callee(java.nio.ByteBuffer buf) {
    fill(buf.getInt()); // Noncompliant {{Loop bound in Sizes.fill() at TaintedSize.java:28 built from binary or text input: ByteBuffer.getInt() at TaintedSize.java:34 (passed at TaintedSize.java:34).}}
  }

  byte[] taintedMax(java.nio.ByteBuffer buf) {
    int count = buf.getInt();
    int max = buf.getInt();
    if (count > max) {
      return null;
    }
    return new byte[count]; // Noncompliant
  }

  byte[] lowerBoundOnly(java.nio.ByteBuffer buf) {
    int n = buf.getInt();
    if (n < 1) {
      return null;
    }
    return new byte[n]; // Noncompliant
  }

  int header(java.io.InputStream in) throws java.io.IOException {
    byte[] h = new byte[4];
    in.read(h);
    int len = (h[0] & 0xFF) << 8 | (h[1] & 0xFF);
    return new byte[len].length; // Noncompliant
  }

  int lineLength(java.io.BufferedReader r) throws java.io.IOException {
    String line = r.readLine();
    return new char[line.length()].length; // Noncompliant
  }

  int field(java.io.BufferedReader r) throws java.io.IOException {
    String[] parts = r.readLine().split(",");
    int n = Integer.parseInt(parts[1]);
    return new int[n].length; // Noncompliant
  }
}
