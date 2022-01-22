package de.peterspace.nftdropper.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Address {
	@Id
	@NotNull
	String address;

	@NotBlank
	String skey;

	@NotBlank
	String vkey;

	@NotNull
	@Min(0)
	Integer tokensMinted;

	@Column(unique = true)
	String assetName;
}