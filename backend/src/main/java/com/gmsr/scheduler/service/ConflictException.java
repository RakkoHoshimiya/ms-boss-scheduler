package com.gmsr.scheduler.service;

/** The client is editing a version which has already changed. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}
