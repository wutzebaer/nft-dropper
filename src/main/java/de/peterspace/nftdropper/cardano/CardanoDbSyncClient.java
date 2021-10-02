package de.peterspace.nftdropper.cardano;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;

import de.peterspace.nftdropper.model.TransactionInputs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class CardanoDbSyncClient {

	private static final String utxoQuery = "select max(encode(t2.hash::bytea, 'hex')) txhash, max(uv.\"index\") txix, max(uv.value) \"value\", max(to2.stake_address_id) stake_address, max(to2.address) source_address\r\n"
			+ "from utxo_view uv\r\n"
			+ "join tx t2 on t2.id = uv.tx_id \r\n"
			+ "join tx_in ti on ti.tx_in_id = uv.tx_id\r\n"
			+ "join tx_out to2 on to2.tx_id = ti.tx_out_id and to2.\"index\" = ti.tx_out_index\r\n"
			+ "where \r\n"
			+ "uv.address = ?\r\n"
			+ "group by uv.id\r\n"
			+ "order by uv.id";

	@Value("${cardano-db-sync.url}")
	String url;

	@Value("${cardano-db-sync.username}")
	String username;

	@Value("${cardano-db-sync.password}")
	String password;

	private HikariDataSource hds;

	@PostConstruct
	public void init() throws SQLException {
		hds = new HikariDataSource();
		hds.setInitializationFailTimeout(60000l);
		hds.setJdbcUrl(url);
		hds.setUsername(username);
		hds.setPassword(password);
		hds.setMaximumPoolSize(30);
		hds.setAutoCommit(false);
	}

	@PreDestroy
	public void shutdown() {
		hds.close();
	}

	public List<TransactionInputs> getOfferFundings(String offerAddress) {
		try (Connection connection = hds.getConnection()) {
			PreparedStatement getTxInput = connection.prepareStatement(utxoQuery);
			getTxInput.setString(1, offerAddress);
			ResultSet result = getTxInput.executeQuery();
			List<TransactionInputs> offerFundings = new ArrayList<TransactionInputs>();
			while (result.next()) {
				offerFundings.add(new TransactionInputs(result.getString(1), result.getInt(2), result.getLong(3), result.getLong(4), result.getString(5)));
			}
			return offerFundings;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
