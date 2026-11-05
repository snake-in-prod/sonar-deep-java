package parity; // parity

import java.util.Map;

class Row {
  private String name;
  private String label;

  String getName() {
    return name;
  }

  String getLabel() {
    return label;
  }

  void setName(String v) {
    name = v;
  }

  void setLabel(String v) {
    label = v;
  }
}

class Reports {
  void counts(Map<String, Integer> stats, int complianceCount) {
    stats.put("compliance", complianceCount);
    stats.put("nonCompliance", complianceCount); // Noncompliant
  }

  // the same combination appears again further down: deliberate
  void totals(Map<String, Integer> stats, int totalCount, StringBuilder log) {
    stats.put("total", totalCount);
    stats.put("subTotal", totalCount);
    log.append("subTotal").append(totalCount);
  }

  void labels(Row target, Row source) {
    target.setName(source.getName());
    target.setLabel(source.getName()); // Noncompliant
  }

  // the label is filled from the name on purpose: the mapping is repeated
  void labelsFromNames(Row target, Row source, Row other) {
    target.setName(source.getName());
    target.setLabel(source.getName());
    other.setLabel(source.getName());
  }
}
