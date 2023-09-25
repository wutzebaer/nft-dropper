package de.peterspace.nftdropper.cardano.exceptions;

@SuppressWarnings("serial")
public class PolicyExpiredException extends Exception {
	public PolicyExpiredException(String message) {
		super(message);
	}
}
