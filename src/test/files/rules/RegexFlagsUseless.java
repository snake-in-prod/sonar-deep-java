package rules;

import java.util.regex.Pattern;

class Regexes {
  Pattern a = Pattern.compile("a.b", Pattern.MULTILINE); // Noncompliant
  Pattern b = Pattern.compile("^a.b$", Pattern.MULTILINE);
  Pattern c = Pattern.compile("abc", Pattern.UNICODE_CASE); // Noncompliant
  Pattern d = Pattern.compile("123", Pattern.CASE_INSENSITIVE); // Noncompliant
  Pattern e = Pattern.compile("abc", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
}
