package de.peterspace.nftdropper.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Value;

@Value
public class Transaction {
	private String signedData;
	private String rawData;
	private String txId;
	private Long fee;
	private Long minOutput;
	private Long txSize;
	private String policyId;
	private String outputs;
	private String inputs;
	private String metaDataJson;
	private String policy;
}
