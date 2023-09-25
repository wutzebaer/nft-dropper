package de.peterspace.nftdropper.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
}