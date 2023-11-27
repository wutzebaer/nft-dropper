package de.peterspace.nftdropper.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Entity
public class HunterRow {
	@Id
	private String stakeAddress;

	@NotNull
	private Long count;

	private String handle;

	@NotNull
	private Integer rank;
}