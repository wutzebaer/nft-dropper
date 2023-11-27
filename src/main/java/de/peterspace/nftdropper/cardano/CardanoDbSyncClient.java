package de.peterspace.nftdropper.cardano;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import de.peterspace.cardanodbsyncapi.client.ApiClient;
import de.peterspace.cardanodbsyncapi.client.RestHandlerApi;
import de.peterspace.cardanodbsyncapi.client.model.OwnerInfo;
import de.peterspace.cardanodbsyncapi.client.model.StakeAddress;
import de.peterspace.cardanodbsyncapi.client.model.TokenDetails;
import de.peterspace.cardanodbsyncapi.client.model.TokenListItem;
import de.peterspace.cardanodbsyncapi.client.model.Utxo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CardanoDbSyncClient {

	@Value("${cardano-db-sync.api}")
	String apiBasePath;

	private RestHandlerApi restHandlerApi;

	@PostConstruct
	public void init() throws SQLException {
		ApiClient apiClient = new ApiClient();
		apiClient.setBasePath(apiBasePath);
		restHandlerApi = new RestHandlerApi(apiClient);
	}

	public List<Utxo> getUtxos(String address) {
		return Collections.unmodifiableList(restHandlerApi.getUtxos(address));
	}

	public String getReturnAddress(String stakeAddress) {
		return restHandlerApi.getReturnAddress(stakeAddress).getAddress();
	}

	public List<String> getHandles(String stakeAddress) {
		return restHandlerApi.getHandles(stakeAddress).stream().map(StakeAddress::getAddress).toList();
	}

	public String getStakeAddress(String address) {
		return restHandlerApi.getStakeAddress(address).getAddress();
	}

	public List<TokenListItem> getPolicyTokens(String policyId) {
		return restHandlerApi.getTokenList(null, null, policyId);
	}

	public List<OwnerInfo> getCurrentCharlyHolders() {
		return restHandlerApi.getOwners("89267e9a35153a419e1b8ffa23e511ac39ea4e3b00452e9d500f2982");
	}

	public TokenDetails getTokenDetails(String policyId, String assetName) {
		return restHandlerApi.getTokenDetails(policyId, assetName);
	}

}
