package de.peterspace.nftdropper.component;

import java.util.List;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import de.peterspace.cardanodbsyncapi.client.model.TokenListItem;
import de.peterspace.nftdropper.cardano.CardanoDbSyncClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class CharlyTokenService {

	@Value("${charly.token}")
	private String charlyToken;

	private final CardanoDbSyncClient cardanoDbSyncClient;

	@PostConstruct
	public void init() throws Exception {
		if (StringUtils.isBlank(charlyToken)) {
			return;
		}
	}

	public List<TokenListItem> getTokens(String policyId) throws DecoderException {
		return cardanoDbSyncClient.getPolicyTokens(policyId);
	}

	public String right(String s, int len) {
		return StringUtils.right(s, len);
	}

}
