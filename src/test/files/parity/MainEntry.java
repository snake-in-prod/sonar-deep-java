package parity; // parity

import java.util.Scanner;

class Tool {
  public static void main(String[] args) {
    Scanner in = new Scanner(System.in);
    String line = in.nextLine(); // Noncompliant
    System.out.println(line);
  }
}
