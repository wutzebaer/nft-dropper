package de.peterspace.nftdropper.model;

import java.util.Date;
import java.util.List;

import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.validation.constraints.NotNull;

import org.json.JSONObject;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;

@Entity
@Data
public class HunterSnapshot {
	@Id
	@GeneratedValue
	long id;

	@NotNull
	Date timestamp;

	@NotNull
	@ElementCollection(fetch = FetchType.EAGER)
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