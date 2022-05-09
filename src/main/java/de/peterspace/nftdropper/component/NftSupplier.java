package de.peterspace.nftdropper.component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import de.peterspace.nftdropper.model.TokenData;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class NftSupplier {

	@Value("${token.dir}")
	private String tokenDir;
	private Path soldFolder;
	private Path sourceFolder;

	@Getter
	private long totalTokens;
	private List<TokenData> availableTokens = new ArrayList<>();

	@PostConstruct
	@Scheduled(cron = "*/1 * * * * *")
	public void init() throws IOException {
		List<TokenData> foundTokens = new ArrayList<>();
		sourceFolder = Paths.get(tokenDir);
		soldFolder = Files.createDirectories(sourceFolder.resolve("sold"));

		Path metaDataPath = sourceFolder.resolve("metadata.json");
		try (BufferedReader bufferedReader = Files.newBufferedReader(metaDataPath);) {
			JSONObject metaData = new JSONObject(new JSONTokener(bufferedReader));
			totalTokens = metaData.keySet().size();
			for (String filename : metaData.keySet()) {
				Path tokenFile = Paths.get(tokenDir, filename);
				if (Files.isRegularFile(tokenFile)) {
					foundTokens.add(new TokenData(filename, metaData.getJSONObject(filename)));
				}
			}
		}
		Collections.shuffle(foundTokens);
		if (availableTokens.size() != foundTokens.size()) {
			log.info("Found {} tokens to sell", foundTokens.size());
		}
		availableTokens = foundTokens;
	}

	public Set<String> getAvailableTokens() {
		return availableTokens.stream().map(TokenData::assetName).collect(Collectors.toSet());
	}

	public List<TokenData> claimTokens(int amount) throws IOException {
		ArrayList<TokenData> tokenDatas = new ArrayList<>(availableTokens.subList(0, Math.min(amount, tokensLeft())));
		removeTokenFromSale(tokenDatas);
		return tokenDatas;
	}

	public Optional<TokenData> getToken(String assetName) {
		Optional<TokenData> tokenData = availableTokens.stream().filter(t -> t.getFilename().equals(assetName)).findFirst();
		return tokenData;
	}

	public void removeTokenFromSale(List<TokenData> tokenDatas) throws IOException {
		for (TokenData tokenData : tokenDatas) {
			Files.move(sourceFolder.resolve(tokenData.getFilename()), soldFolder.resolve(tokenData.getFilename()), StandardCopyOption.REPLACE_EXISTING);
		}
		availableTokens.removeAll(tokenDatas);
	}

	public int tokensLeft() {
		return availableTokens.size();
	}

}
