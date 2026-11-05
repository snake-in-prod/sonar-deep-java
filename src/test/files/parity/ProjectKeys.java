package parity; // parity

import java.util.Map;

class NodeId {
  final String value;

  NodeId(String value) {
    this.value = value;
  }
}

class Nodes {
  private Map<NodeId, String> names;

  String name(String id) {
    return names.get(id); // Noncompliant
  }

  String byId(NodeId id) {
    return names.get(id);
  }
}
