package nullness;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class Delegates {
  String waitFor(Future<String> f) throws Exception {
    return f.get(5, TimeUnit.SECONDS);
  }

  int direct(Future<String> f) throws Exception {
    return f.get(1, TimeUnit.SECONDS).length(); // Noncompliant
  }

  interface ProxyFuture<T> extends Future<T> {
  }

  interface Client {
    ProxyFuture<String> send();
  }

  int chained(Client c) throws Exception {
    return c.send().get(5, TimeUnit.SECONDS).length(); // Noncompliant
  }

  int wrapper(Future<String> f) throws Exception {
    return waitFor(f).length(); // Noncompliant
  }

  List<String> names(boolean b) {
    if (b) {
      return null;
    }
    return List.of("a");
  }

  boolean useList(boolean b) {
    List<String> l = names(b);
    return l.contains("a"); // Noncompliant
  }

  int loop(boolean b) {
    int n = 0;
    for (String s : names(b)) { // Noncompliant
      n += s.length();
    }
    return n;
  }

  String one(boolean b) {
    if (b) {
      return null;
    }
    return "x";
  }

  int notCollection(boolean b) {
    return one(b).length();
  }
}

@org.mapstruct.Mapper
interface PersonMapper {
  Dto toDto(Entity e);
}

class PersonMapperImpl implements PersonMapper {
  @Override
  public Dto toDto(Entity e) {
    if (e == null) {
      return null;
    }
    Dto d = new Dto();
    d.name = e.name;
    return d;
  }
}

class Entity {
  String name;
}

class Dto {
  String name;

  String name() {
    return name;
  }
}

class UseMapper {
  PersonMapper mapper = new PersonMapperImpl();

  int map(Entity e) {
    return mapper.toDto(e).name().length(); // Noncompliant
  }

  int fresh() {
    return mapper.toDto(new Entity()).name().length();
  }

  Dto service(Entity e) {
    return mapper.toDto(e);
  }

  int throughService(Entity e) {
    return service(e).name().length();
  }
}
