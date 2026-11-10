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

  void markVia(int[] slots, int i) {
    mark(slots, i);
  }

  void markFreeVia(int[] slots) {
    markVia(slots, firstFree(slots)); // Noncompliant
  }

  String unwrap(String str, String wrapToken) {
    if (Strings.CS.startsWith(str, wrapToken) && Strings.CS.endsWith(str, wrapToken)) {
      return str.substring(wrapToken.length(), str.lastIndexOf(wrapToken));
    }
    return str;
  }

  String afterContains(String s, String t) {
    return s.contains(t) ? s.substring(s.indexOf(t)) : s;
  }

  String afterHelper(String s, String t) {
    if (StringUtils.contains(s, t)) {
      return s.substring(s.indexOf(t));
    }
    return s;
  }

  String afterGuard(String s, String t) {
    if (!s.endsWith(t)) {
      return s;
    }
    return s.substring(0, s.lastIndexOf(t));
  }

  char afterIndexCheck(String s) {
    if (s.indexOf('x') != -1) {
      int i = s.indexOf('x');
      return s.charAt(i);
    }
    return ' ';
  }

  char afterNegativeGuard(String s) {
    if (s.indexOf('x') < 0) {
      throw new IllegalArgumentException(s);
    }
    return s.charAt(s.indexOf('x'));
  }

  String fromCheckedList(List<String> l) {
    return l.contains("a") && l.indexOf("a") > -1 ? l.get(l.indexOf("a")) : null;
  }

  String otherString(String s, String t, String u) {
    if (u.contains(t)) {
      return s.substring(s.indexOf(t)); // Noncompliant
    }
    return s;
  }

  String notContained(String s, String t) {
    if (s.contains(t)) {
      return s;
    }
    return s.substring(s.indexOf(t)); // Noncompliant
  }

  String eitherContains(String s, String t, boolean b) {
    if (b || s.contains(t)) {
      return s.substring(s.indexOf(t)); // Noncompliant
    }
    return s;
  }
}

class Strings {
  static final Strings CS = new Strings();

  boolean startsWith(CharSequence s, CharSequence prefix) {
    return s.toString().startsWith(prefix.toString());
  }

  boolean endsWith(CharSequence s, CharSequence suffix) {
    return s.toString().endsWith(suffix.toString());
  }
}

class StringUtils {
  static boolean contains(CharSequence s, CharSequence t) {
    return s.toString().contains(t);
  }
}
