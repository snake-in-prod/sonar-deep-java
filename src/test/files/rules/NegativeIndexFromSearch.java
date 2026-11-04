package rules;

import java.util.List;

class Search {
  String after(String s) {
    return s.substring(s.indexOf(':')); // Noncompliant
  }

  String afterPlusOne(String s) {
    return s.substring(s.indexOf(':') + 1);
  }

  char viaLocal(String s) {
    int i = s.indexOf('x');
    return s.charAt(i); // Noncompliant
  }

  char checked(String s) {
    int i = s.indexOf('x');
    if (i < 0) {
      return ' ';
    }
    return s.charAt(i);
  }

  char at(String s, int i) {
    return s.charAt(i);
  }

  char viaCallee(String s) {
    return at(s, s.indexOf('x')); // Noncompliant
  }

  String fromList(List<String> l) {
    return l.get(l.indexOf("a")); // Noncompliant
  }

  int firstFree(int[] slots) {
    for (int i = 0; i < slots.length; i++) {
      if (slots[i] == 0) {
        return i;
      }
    }
    return -1;
  }

  int take(int[] slots) {
    return slots[firstFree(slots)]; // Noncompliant
  }

  void mark(int[] slots, int i) {
    slots[i] = 1;
  }

  void markFree(int[] slots) {
    mark(slots, firstFree(slots)); // Noncompliant
  }
}
