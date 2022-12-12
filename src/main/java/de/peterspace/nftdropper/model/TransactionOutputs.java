package de.peterspace.nftdropper.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.json.JSONObject;

import lombok.Data;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class TransactionOutputs {

	private Map<String, Map<String, Long>> outputs = new HashMap<>();

	@Synchronized
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

	@Synchronized
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

	@Synchronized
	public String toCliFormat(String address) {
		return outputs
				.entrySet().stream()
				.filter(addressEntry -> addressEntry.getKey().equals(address))
				.map(addressEntry -> addressEntry.getKey().split("#")[0] + "+" +
						addressEntry.getValue()
								.entrySet().stream()
								.map(currencyEntry -> (currencyEntry.getValue() + " " + currencyEntry.getKey()).trim())
								.sorted()
								.collect(Collectors.joining("+"))

				)
				.findFirst().orElse("");
	}

	@Synchronized
	public String toString() {
		return new JSONObject(outputs).toString(3);
	}

}
