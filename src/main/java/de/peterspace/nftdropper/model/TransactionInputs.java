package de.peterspace.nftdropper.model;

import org.json.JSONObject;

import com.fasterxml.jackson.databind.ObjectMapper;

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

	@Override
	public String toString() {
		try {
			return new JSONObject(new ObjectMapper().writeValueAsString(this)).toString(3);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
