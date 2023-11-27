package de.peterspace.nftdropper.component;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import de.peterspace.nftdropper.cardano.CardanoDbSyncClient;
import de.peterspace.nftdropper.model.HunterRow;
import de.peterspace.nftdropper.repository.HunterRowRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class HunterService {

	@Value("${charly.token}")
	private String charlyToken;

	@Value("${charly.hunter3.start}")
	private String hunterStartString;
	private Instant hunterStartTimestamp;

	@Value("${charly.hunter3.end}")
	private String hunterEndString;
	private Instant hunterEndTimestamp;

	private final HunterRowRepository hunterRowRepository;
	private final CardanoDbSyncClient cardanoDbSyncClient;

	private Map<String, HunterRow> topList;

	@PostConstruct
	public void init() throws ParseException {
		if (StringUtils.isBlank(charlyToken)) {
			return;
		}

		hunterStartTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mmz").parse(hunterStartString + "UTC").toInstant();
		hunterEndTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mmz").parse(hunterEndString + "UTC").toInstant();
		if (Instant.now().isBefore(hunterStartTimestamp)) {
			log.info("Deleteing hunter rows, hunt not started yet");
			hunterRowRepository.deleteAll();
		}

		topList = hunterRowRepository.findAll().stream().collect(Collectors.toMap(HunterRow::getStakeAddress, Function.identity()));
	}

	public void addMint(String stakeAddress, Integer count) {
		if (Instant.now().isBefore(hunterStartTimestamp)) {
			log.info("Ignoring Transaction, hunt not started yet");
			return;
		}
		if (Instant.now().isAfter(hunterEndTimestamp)) {
			log.info("Ignoring Transaction, hunt has finished");
			return;
		}
		HunterRow hunterRow = topList.computeIfAbsent(stakeAddress, sa -> {
			HunterRow hr = new HunterRow();
			hr.setCount(0l);
			hr.setHandle(cardanoDbSyncClient.getHandles(stakeAddress).stream().findFirst().orElse(null));
			hr.setRank(0);
			hr.setStakeAddress(sa);
			return hr;
		});
		hunterRow.setCount(hunterRow.getCount() + count);
		hunterRowRepository.save(hunterRow);
	}

	@Scheduled(cron = "* * * * * *")
	public void checkEnd() {
		if (Instant.now().isAfter(hunterEndTimestamp) && !topList.values().stream().anyMatch(r -> r.getRank() > 0)) {
			log.info("Setting Ranks");
			ArrayList<HunterRow> rows = new ArrayList<>(topList.values());
			rows.sort(Comparator.comparingLong(r -> r.getCount()));
			for (int i = 1; i <= rows.size(); i++) {
				rows.get(i - 1).setRank(i);
			}
			hunterRowRepository.saveAll(rows);
		}
	}

	public List<HunterRow> getRows() {
		return new ArrayList<>(topList.values());
	}

}
