package parity; // test code: counted for call-site statistics only


class RepositoryFindersTest {
  void t(UserRepository r, Versioned<String> v) {
    r.findByName("1").id();
    r.findByName("2").id();
    r.findByName("3").id();
    r.findByName("4").id();
    r.findByName("5").id();
    v.getVersion().length();
    v.getVersion().length();
    v.getVersion().length();
    v.getVersion().length();
  }
}
