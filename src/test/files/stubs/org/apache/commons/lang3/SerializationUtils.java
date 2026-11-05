package org.apache.commons.lang3;

public class SerializationUtils {
  public static <T extends java.io.Serializable> T clone(T object) {
    return object;
  }
}
