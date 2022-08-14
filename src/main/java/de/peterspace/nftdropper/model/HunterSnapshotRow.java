package de.peterspace.nftdropper.model;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Transient;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class HunterSnapshotRow {

	@Id
	@NotBlank
	@Column(name = "\"group\"")
	@EqualsAndHashCode.Include
	private String group;

	@NotBlank
	private String address;

	private String handle;

	@NotNull
	private long quantity;

	@NotNull
	private long startQuantity;

	@Transient
	private int rank;

	@NotNull
	private Date timestamp;
}