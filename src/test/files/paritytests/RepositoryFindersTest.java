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

  void settings(Settings s) {
    for (String k : new String[] {"a", "b", "c", "d"}) {
      if (s.lookup(k) != null && s.section(k) != null) {
        System.out.println(k);
      }
    }
  }

  void more(Settings s) {
    assert s.lookup("x") != null;
    assert s.lookup("y") != null;
    assert s.lookup("z") != null;
    assert s.section("x") != null;
    assert s.section("y") != null;
    assert s.section("z") != null;
  }
}
