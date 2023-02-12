package de.peterspace.nftdropper.model;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.validation.constraints.NotNull;

import lombok.Data;

@Data
@Entity
public class CharlyJackpotCounter {

	@Id
	private Long id = 1l;

	@NotNull
	private Integer count = 0;
}