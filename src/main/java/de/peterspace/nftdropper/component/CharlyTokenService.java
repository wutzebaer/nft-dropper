package de.peterspace.nftdropper.component;

import java.util.List;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import de.peterspace.cardanodbsyncapi.client.model.OwnerInfo;
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

	@lombok.Value
	public static class Circulation {
		long totalSupply;
		long circulatingSupply;
	}
	@Cacheable("getCharlySupply")
	public Circulation getCharlySupply() {
		var blacklist = List.of(
				"addr1vxgts9tzm59h72uetsg48m3tj4z6h0gqz3m7pdm0gapwxxslqglt9",
				"addr1v9gs0trlcmyty7jakcewjs3h00a7xrzyd5wnyfrpeg4wjts0ugx63",
				"stake1uxkxtajzhlmnlglr5dhrdfx36ry597wvr2k5utlm6eu8susrny2uj",
				"stake1ux3q72uz5ztmyvuz7qwgv8qw3akpr2pnh4ed3wzudddt33qh67tzj",
				"stake1u94eatrzsm59pnlncqtx0jds2hyv4u46pdjjgw9avus5qhq5p08fc",
				"stake1u94vpc75fv6mq4vcupew454mf97wygg54shprlnwy8f5r5spul7ju");

		List<OwnerInfo> currentCharlyHolders = cardanoDbSyncClient.getCurrentCharlyHolders();
		long totalSupply = currentCharlyHolders.stream().mapToLong(h -> h.getAmount()).sum();
		long circulatingSupply = currentCharlyHolders.stream().filter(h -> !blacklist.contains(h.getAddress())).mapToLong(h -> h.getAmount()).sum();
		return new Circulation(totalSupply, circulatingSupply);
	}

}
