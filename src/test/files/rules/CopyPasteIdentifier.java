package rules;

class Point {
  double x;
  double y;
}

class Geometry {
  double dist(Point a, Point b) {
    double dx = a.x - b.x;
    double dy = a.y - b.x; // Noncompliant
    return Math.sqrt(dx * dx + dy * dy);
  }

  double ok(Point a, Point b) {
    double dx = a.x - b.x;
    double dy = a.y - b.y;
    return dx + dy;
  }
}
