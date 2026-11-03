package javax.servlet.http;

public interface HttpServletResponse {
  void setHeader(String name, String value);

  void sendRedirect(String location) throws java.io.IOException;
}
