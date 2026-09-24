package com.portway.app.api;

/** A request that is well-formed but asks for something invalid, such as a bad package name. */
public class InvalidRequestException extends RuntimeException {

  public InvalidRequestException(String message) {
    super(message);
  }
}
