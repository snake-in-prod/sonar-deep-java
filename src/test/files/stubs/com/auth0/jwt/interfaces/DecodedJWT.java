package com.auth0.jwt.interfaces;

public interface DecodedJWT {
  Claim getClaim(String name);
}
