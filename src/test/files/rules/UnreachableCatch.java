package rules;

class ValidationException extends RuntimeException {
}

class Validator {
  void check(String s) {
    if (s.isEmpty()) {
      throw new ValidationException();
    }
  }

  int compute(int a) {
    return a * 2;
  }

  void use(String s) {
    try {
      check(s);
    } catch (ValidationException e) {
      System.out.println("invalid");
    }
    try {
      compute(1);
    } catch (ValidationException e) { // Noncompliant
      System.out.println("never");
    }
  }
}
