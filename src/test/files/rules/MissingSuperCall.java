package rules;

class Base {
  void onStop() {
    System.out.println("base");
  }
}

class A1 extends Base {
  @Override
  void onStop() {
    super.onStop();
  }
}

class A2 extends Base {
  @Override
  void onStop() {
    super.onStop();
  }
}

class A3 extends Base {
  @Override
  void onStop() {
    super.onStop();
  }
}

class A4 extends Base {
  @Override
  void onStop() {
    super.onStop();
  }
}

class A5 extends Base {
  @Override
  void onStop() {
    System.out.println("no super"); // Noncompliant
  }
}
