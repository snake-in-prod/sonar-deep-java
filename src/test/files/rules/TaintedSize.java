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
}
