package parity; // parity

@lombok.Data
class Account {
  private String owner;
  private int balance;
}

class Notifier {
  void notify(String who) {
    System.out.println(who.trim());
  }

  void run(Account a) {
    notify(a.getOwner()); // Noncompliant {{"Notifier.notify()" dereferences its parameter "who" on every path, but a field that the Lombok-generated equals() compares to null is passed here.}}
    notify("x");
  }
}
