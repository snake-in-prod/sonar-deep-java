package rules;

enum Mode {
  FAST, SLOW, LEGACY
}

class Runner {
  int speed(Mode m) {
    switch (m) { // Noncompliant
      case FAST:
        return 1;
      case SLOW:
        return 2;
      case LEGACY:
        return 3;
      default:
        return 0;
    }
  }

  int start() {
    return speed(Mode.FAST) + speed(Mode.SLOW);
  }
}
