package nullness;

import java.util.List;
import java.util.Map;

class Groups {
  private Map<String, List<String>> map;
  private String defaultGroup = "g";

  List<String> find(String group, String id) {
    List<String> result = null;
    if (map != null) {
      result = map.get(id);
    }
    return result;
  }

  List<String> find(String id) {
    return find(defaultGroup, id);
  }

  int size(String id) {
    return find(id).size(); // Noncompliant
  }

  int checkedTwice(String id) {
    return find(id) != null ? find(id).size() : 0;
  }
}
