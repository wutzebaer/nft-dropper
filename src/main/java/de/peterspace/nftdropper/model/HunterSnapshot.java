package de.peterspace.nftdropper.model;

import java.util.Date;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.OneToMany;
import javax.validation.constraints.NotNull;

import org.json.JSONObject;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Entity
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class HunterSnapshot {
	@Id
	@GeneratedValue
	long id;

	@NotNull
	Date timestamp;

	@NotNull
	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
	@JoinColumn(name = "hunter_snapshot_id")
	@EqualsAndHashCode.Include
	private List<HunterSnapshotRow> hunterSnapshotRows;

	@Override
	public String toString() {
		try {
			return new JSONObject(new ObjectMapper().writeValueAsString(this)).toString(3);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}