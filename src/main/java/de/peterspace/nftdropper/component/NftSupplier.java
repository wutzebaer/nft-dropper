package de.peterspace.nftdropper.component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.PostConstruct;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import de.peterspace.nftdropper.model.TokenData;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class NftSupplier {

	@Value("${token.dir}")
	private String tokenDir;
	private Path soldFolder;
	private Path sourceFolder;

	private List<TokenData> availableTokens = new ArrayList<>();

	@PostConstruct
	public void init() throws IOException {
		sourceFolder = Paths.get(tokenDir);
		soldFolder = Files.createDirectories(sourceFolder.resolve("sold"));

		Path metaDataPath = sourceFolder.resolve("metadata.json");
		JSONObject metaData = new JSONObject(new JSONTokener(Files.newBufferedReader(metaDataPath)));
		for (String filename : metaData.keySet()) {
			Path tokenFile = Paths.get(tokenDir, filename);
			if (Files.isRegularFile(tokenFile)) {
				availableTokens.add(new TokenData(filename, metaData.getJSONObject(filename)));
			}
		}
		Collections.shuffle(availableTokens);
		log.info("Found {} tokens to sell", availableTokens.size());
	}

	public List<TokenData> getTokens(int amount) {
		return new ArrayList<>(availableTokens.subList(0, amount));
	}

	public void markTokenSold(List<TokenData> tokenDatas) throws IOException {
		availableTokens.removeAll(tokenDatas);
		for (TokenData tokenData : tokenDatas) {
			Files.move(sourceFolder.resolve(tokenData.getFilename()), soldFolder.resolve(tokenData.getFilename()));
		}
	}

	public int tokensLeft() {
		return availableTokens.size();
	}

}
