package rules;

class Late {
  char first(String s, int i) {
    char c = s.charAt(i); // Noncompliant
    if (i < s.length()) {
      return c;
    }
    return ' ';
  }

  int early(int[] a, int i) {
    if (i < a.length) {
      return a[i];
    }
    return 0;
  }
}
