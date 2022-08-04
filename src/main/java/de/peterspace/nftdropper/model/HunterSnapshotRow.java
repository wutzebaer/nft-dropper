package de.peterspace.nftdropper.model;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.Transient;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@Entity
@Table(indexes = { @Index(columnList = "\"group\"") })
public class HunterSnapshotRow {
	@Id
	@GeneratedValue
	@EqualsAndHashCode.Exclude
	long id;

	@NotBlank
	@Column(name = "\"group\"")
	String group;

	@NotBlank
	String address;

	String handle;

	@NotNull
	long quantity;

	@Transient
	int rank;
}