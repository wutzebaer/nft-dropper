import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public class FoxhuntFixer {
	public static void main(String[] args) throws JSONException, IOException {
		Path input = Path.of("C:\\github\\nft-dropper\\nft-source\\metadata.json");
		Path output = Path.of("C:\\github\\nft-dropper\\nft-source\\metadata.out.json");

		JSONObject jsonObject = new JSONObject(new JSONTokener(new FileInputStream(input.toFile())));

		for (String key : jsonObject.keySet()) {
			JSONObject token = jsonObject.getJSONObject(key);
			JSONArray attributes = token.getJSONArray("attributes");
			for (int i = 0; i < attributes.length(); i++) {
				JSONObject attribute = attributes.getJSONObject(i);
				attribute.put(attribute.getString("trait_type"), attribute.getString("value"));
				attribute.remove("trait_type");
				attribute.remove("value");
			}
			token.remove("image");
			token.put("homepage", "https://www.foxhuntnft.com/");
		}

		Files.writeString(output, jsonObject.toString(3));
	}
}
