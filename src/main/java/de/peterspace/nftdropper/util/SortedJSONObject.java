package de.peterspace.nftdropper.util;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public class SortedJSONObject extends JSONObject {

	public SortedJSONObject() {
		super();
		try {
			Field mapField = JSONObject.class.getDeclaredField("map");
			mapField.setAccessible(true);
			mapField.set(this, new LinkedHashMap());
			mapField.setAccessible(false);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
