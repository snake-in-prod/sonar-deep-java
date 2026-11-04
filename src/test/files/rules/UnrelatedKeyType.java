package rules;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class Users {
  private final Map<Long, String> names = new HashMap<>();

  String byId(int id) {
    return names.get(id); // Noncompliant
  }

  String byLong(long id) {
    return names.get(id);
  }

  boolean listed(List<String> l, Integer n) {
    return l.contains(n); // Noncompliant
  }
}

class Dao<K, V> {
  private final Map<K, V> cache = new HashMap<>();

  V find(Object key) {
    return cache.get(key);
  }
}

class Repository {
  private final Dao<String, Users> dao = new Dao<>();

  Users load(Long id, String name) {
    Users u = dao.find(name);
    return u != null ? u : dao.find(id); // Noncompliant
  }
}
