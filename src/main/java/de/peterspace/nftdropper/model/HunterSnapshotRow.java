package de.peterspace.nftdropper.model;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.validation.constraints.NotBlank;

import lombok.Data;

@Embeddable
@Data
public class HunterSnapshotRow {
	@NotBlank
	@Column(name = "\"GROUP\"")
	String group;

	@NotBlank
	String address;

	@NotBlank
	String handle;

	@NotBlank
	long quantity;
}