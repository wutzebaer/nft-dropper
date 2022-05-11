package de.peterspace.nftdropper.model;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class TransactionOutputs {

	private Map<String, Map<String, Long>> outputs = new HashMap<>();

	public void add(String address, String currency, long amount) {
		Map<String, Long> addressMap = outputs.computeIfAbsent(address, k -> new HashMap<>());
		Long currentAmount = addressMap.computeIfAbsent(currency, k -> 0l);
		if (currentAmount + amount != 0) {
			addressMap.put(currency, currentAmount + amount);
		} else {
			addressMap.remove(currency);
		}
		if (addressMap.isEmpty()) {
			outputs.remove(address);
		}
	}

	public List<String> toCliFormat() {
		return outputs
				.entrySet().stream()
				.map(addressEntry -> addressEntry.getKey().split("#")[0] + "+" +
						addressEntry.getValue()
								.entrySet().stream()
								.map(currencyEntry -> (currencyEntry.getValue() + " " + currencyEntry.getKey()).trim())
								.collect(Collectors.joining("+"))

				)
				.collect(Collectors.toList());
	}

	public String toCliFormat(String address) {
		return outputs
				.entrySet().stream()
				.filter(addressEntry -> addressEntry.getKey().equals(address))
				.map(addressEntry -> addressEntry.getKey().split("#")[0] + "+" +
						addressEntry.getValue()
								.entrySet().stream()
								.map(currencyEntry -> (currencyEntry.getValue() + " " + currencyEntry.getKey()).trim())
								.collect(Collectors.joining("+"))

				)
				.findFirst().orElse("");
	}

	public String toString() {
		return new JSONObject(outputs).toString(3);
	}

	private String formatCurrency(String policyId, String assetName) {
		if (StringUtils.isBlank(assetName)) {
			return policyId;
		} else {
			return policyId + "." + Hex.encodeHexString(assetName.getBytes(StandardCharsets.UTF_8));
		}
	}

}
