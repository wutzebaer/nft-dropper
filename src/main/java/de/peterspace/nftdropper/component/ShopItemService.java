package de.peterspace.nftdropper.component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.PostConstruct;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.stereotype.Component;
import org.springframework.util.ResourceUtils;

import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class ShopItemService {

	@Value
	public static class ShopItem {
		String assetName;
		JSONObject metaData;
	}

	@org.springframework.beans.factory.annotation.Value("${token.dir}")
	private String tokenDir;

	@Getter
	private List<ShopItem> shopItems = new ArrayList<>();

	@PostConstruct
	public void init() throws FileNotFoundException, IOException {
		File file = Path.of(tokenDir).resolve("metadata_shop.json").toFile();
		if (file.exists()) {
			try (InputStream in = new FileInputStream(file)) {
				JSONObject sourceData = new JSONObject(new JSONTokener(in));
				for (String assetName : sourceData.keySet()) {
					JSONObject jsonObject = sourceData.getJSONObject(assetName);
					for (String attributeKey : jsonObject.keySet()) {
						JSONArray optJSONArray = jsonObject.optJSONArray(attributeKey);
						if (optJSONArray != null) {
							jsonObject.put(attributeKey, StringUtils.abbreviate(StringUtils.join(optJSONArray.toList(), ""), 400));
						}
					}
					jsonObject.put("name", jsonObject.getString("name").split(" - ")[1]);

					final long prime = 769;
					long price = jsonObject.getLong("price");
					price = prime * (long) (price / prime);
					String hash = DigestUtils.sha256Hex("" + price);
					jsonObject.put("price", hash);

					shopItems.add(new ShopItem(assetName, jsonObject));
				}
			}
			shopItems.sort(Comparator.comparing(ShopItem::getAssetName));
		}
	}

}
