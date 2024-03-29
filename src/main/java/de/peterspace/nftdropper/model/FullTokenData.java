package de.peterspace.nftdropper.model;

import org.json.JSONObject;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.Transient;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FullTokenData {

	@NotBlank
	private String policyId;

	@NotBlank
	private String name;

	@NotNull
	private Long quantity;

	@NotBlank
	private String txId;

	@NotBlank
	private String json;

	@Transient
	private JSONObject metadata;

	private Long invalid_before;

	private Long invalid_hereafter;

	@NotNull
	private Long slotNo;

	@NotNull
	private Long blockNo;

	@NotNull
	private Long epochNo;

	@NotNull
	private Long epochSlotNo;

	@NotNull
	private Long tid;

	@NotNull
	private Long mintid;

	private String policy;

	@NotNull
	private Long totalSupply;

	@NotNull
	private String fingerprint;

}
