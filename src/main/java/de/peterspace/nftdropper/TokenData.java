package de.peterspace.nftdropper;

import org.json.JSONObject;

import lombok.Value;

@Value
public class TokenData {
	String filename;
	JSONObject metaData;
}
