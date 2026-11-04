package rules;

import java.util.regex.Pattern;

class Regexes {
  boolean digits(String s) {
    return s.matches("[\\d]+"); // Noncompliant
  }

  String[] parts(String s) {
    return s.split("(^|-)"); // Noncompliant
  }

  String strip(String file) {
    return file.replaceFirst(".txt", ""); // Noncompliant
  }

  String fine(String file) {
    return file.replaceFirst("\\.txt$", "") + file.replaceAll("[.]", "_") + Pattern.compile("(ab|cd)").pattern();
  }

  String[] words(String s) {
    return s.split("\\s+");
  }
}
