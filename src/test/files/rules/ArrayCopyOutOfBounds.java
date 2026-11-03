package rules;

import java.util.Arrays;

class Copies {
  int[] copy(int[] src) {
    int[] dst = new int[4];
    System.arraycopy(src, 0, dst, 0, 8); // Noncompliant
    System.arraycopy(src, 0, dst, 0, 4);
    return Arrays.copyOfRange(src, 5, 2); // Noncompliant
  }
}
