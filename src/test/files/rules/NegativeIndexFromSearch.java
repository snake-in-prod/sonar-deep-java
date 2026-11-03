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

  String fromList(List<String> l) {
    return l.get(l.indexOf("a")); // Noncompliant
  }
}
