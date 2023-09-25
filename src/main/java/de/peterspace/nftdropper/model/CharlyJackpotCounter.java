package de.peterspace.nftdropper.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Entity
public class CharlyJackpotCounter {

	@Id
	private Long id = 1l;

	@NotNull
	private Integer count = 0;
}