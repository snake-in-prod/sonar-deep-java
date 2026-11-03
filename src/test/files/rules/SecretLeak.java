package rules;

class Config {
  String getPassword() {
    return "secret";
  }
}

class Connector {
  private final org.slf4j.Logger log;
  private String apiKey;

  Connector(org.slf4j.Logger log) {
    this.log = log;
  }

  void connect(Config config) {
    log.debug("connecting with " + config.getPassword()); // Noncompliant
    log.debug("hash " + hash(config.getPassword()));
    System.out.println(apiKey); // Noncompliant
  }

  void check(String password) {
    if (password.isEmpty()) {
      throw new IllegalStateException("bad password " + password); // Noncompliant
    }
  }

  static String hash(String s) {
    return Integer.toHexString(s.hashCode());
  }
}
