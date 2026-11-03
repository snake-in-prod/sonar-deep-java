package rules;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.servlet.http.HttpServletRequest;

class Repo {
  private final Connection conn;

  Repo(Connection conn) {
    this.conn = conn;
  }

  void find(String id) throws SQLException {
    Statement st = conn.createStatement();
    st.executeQuery("SELECT * FROM t WHERE id = " + id); // Noncompliant
  }

  void safe(int id) throws SQLException {
    conn.createStatement().executeQuery("SELECT * FROM t WHERE id = " + id);
  }
}

class Controller {
  private final Repo repo;

  Controller(Repo repo) {
    this.repo = repo;
  }

  void handle(HttpServletRequest request) throws SQLException {
    repo.find(request.getParameter("id"));
    String n = request.getParameter("n");
    repo.safe(Integer.parseInt(n));
  }
}
