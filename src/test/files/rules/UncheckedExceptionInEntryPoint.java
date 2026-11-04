package rules;

class Worker implements Runnable {
  private final String input;

  Worker(String input) {
    this.input = input;
  }

  @Override
  public void run() {
    int n = parse(input); // Noncompliant
    System.out.println(n);
  }

  private int parse(String s) {
    return Integer.parseInt(s);
  }
}

class SafeWorker implements Runnable {
  @Override
  public void run() {
    try {
      System.out.println(Integer.parseInt("1"));
    } catch (NumberFormatException e) {
      System.out.println("bad");
    }
  }
}

class Cli {
  public static void main(String[] args) {
    java.util.Scanner in = new java.util.Scanner(System.in); // Noncompliant
    System.out.println(in.nextInt() + java.util.List.of("a").iterator().next());
  }
}
