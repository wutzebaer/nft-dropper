package de.peterspace.nftdropper.cardano.exceptions;

@SuppressWarnings("serial")
public class OutputTooSmallUTxOException extends Exception {

	public OutputTooSmallUTxOException(String message) {
		super(message);
	}

}
