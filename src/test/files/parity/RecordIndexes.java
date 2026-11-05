package parity; // parity

import java.io.BufferedReader;
import java.io.IOException;

class Columns {
  static String pick(String[] row, int column) {
    return row[column]; // Noncompliant
  }

  static int sum(int[] values, int count) {
    int s = 0;
    for (int i = 0; i < count; i++) { // Noncompliant
      s += values[i];
    }
    return s;
  }

  String read(BufferedReader in, String[] row, int[] values) throws IOException {
    String[] record = in.readLine().split(",");
    int column = Integer.parseInt(record[0]);
    int count = Integer.parseInt(record[1]);
    return pick(row, column) + sum(values, count); // Noncompliant
  }
}
