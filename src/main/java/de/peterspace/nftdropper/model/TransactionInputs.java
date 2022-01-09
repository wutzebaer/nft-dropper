package de.peterspace.nftdropper.model;

import lombok.Value;

@Value
public class TransactionInputs {
	String txhash;
	int txix;
	long value;
	long stakeAddressId;
	String sourceAddress;
	String policyId;
	String assetName;
	String metaData;
}
