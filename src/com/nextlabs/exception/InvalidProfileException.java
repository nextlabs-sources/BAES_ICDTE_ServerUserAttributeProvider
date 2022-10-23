package com.nextlabs.exception;

public class InvalidProfileException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public InvalidProfileException(String message) {
		super(message);
	}

	public InvalidProfileException(String message, Throwable throwable) {
		super(message, throwable);
	}
}
