package parity; // parity

import java.io.File;

class Cleanup {
  void a(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void b(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void c(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void d(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void e(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void g(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void h(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void i(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void j(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void k(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void l(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void m(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void n(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void o(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void p(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void q(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void r(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void s(File f) { if (!f.delete()) { throw new IllegalStateException(); } }
  void t(File f) { if (!f.delete()) { throw new IllegalStateException(); } }

  void ignored(File f) {
    f.delete(); // Noncompliant
  }

  void mkdirsOnce(File f) {
    f.mkdirs();
  }
}
