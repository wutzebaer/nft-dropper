package de.peterspace.nftdropper.model;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import lombok.Value;

@Value
public class TokenData {
	String filename;
	JSONObject metaData;

	public String assetName() {
		return StringUtils.left(metaData.getString("name").replaceAll("[^a-zA-Z0-9]", ""), 32);
	}
}
