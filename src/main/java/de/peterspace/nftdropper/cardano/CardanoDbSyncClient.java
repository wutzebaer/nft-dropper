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

	private static final String utxoQuery = "select uv.id uvid, max(encode(t2.hash::bytea, 'hex')) txhash, max(uv.\"index\") txix, max(uv.value) \"value\", max(to2.stake_address_id) stake_address, max(to2.address) source_address, '' policyId, '' assetName, null metadata\r\n"
			+ "from utxo_view uv\r\n"
			+ "join tx t2 on t2.id = uv.tx_id \r\n"
			+ "join tx_in ti on ti.tx_in_id = uv.tx_id\r\n"
			+ "join tx_out to2 on to2.tx_id = ti.tx_out_id and to2.\"index\" = ti.tx_out_index\r\n"
			+ "join tx_out to3 on to3.tx_id = uv.tx_id and to3.\"index\" = uv.\"index\" \r\n"
			+ "left join ma_tx_out mto on mto.tx_out_id=to3.id\r\n"
			+ "where \r\n"
			+ "uv.address = ? \r\n"
			+ "group by uv.id\r\n"
			+ "union\r\n"
			+ "select \r\n"
			+ "uv.id uvid, \r\n"
			+ "max(encode(t2.hash::bytea, 'hex')) txhash, \r\n"
			+ "max(uv.\"index\") txix, \r\n"
			+ "max(mto.quantity) \"value\", \r\n"
			+ "max(to2.stake_address_id) stake_address, \r\n"
			+ "max(to2.address) source_address, \r\n"
			+ "encode(ma.\"policy\" ::bytea, 'hex') policyId, \r\n"
			+ "convert_from(ma.name, 'UTF8') assetName,\r\n"
			+ "(select json->encode(ma.\"policy\" ::bytea, 'hex')->convert_from(ma.name, 'UTF8') from tx_metadata tm where tm.tx_id = (select max(tx_id) from ma_tx_mint mtm where mtm.ident=ma.id) and key=721) metadata\r\n"
			+ "from utxo_view uv\r\n"
			+ "join tx t2 on t2.id = uv.tx_id \r\n"
			+ "join tx_in ti on ti.tx_in_id = uv.tx_id\r\n"
			+ "join tx_out to2 on to2.tx_id = ti.tx_out_id and to2.\"index\" = ti.tx_out_index\r\n"
			+ "join tx_out to3 on to3.tx_id = uv.tx_id and to3.\"index\" = uv.\"index\" \r\n"
			+ "join ma_tx_out mto on mto.tx_out_id=to3.id\r\n"
			+ "join multi_asset ma on ma.id=mto.ident \r\n"
			+ "where \r\n"
			+ "uv.address = ? \r\n"
			+ "group by uv.id, ma.id\r\n"
			+ "order by uvid, policyId, assetName";

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
			getTxInput.setString(2, offerAddress);
			ResultSet result = getTxInput.executeQuery();
			List<TransactionInputs> offerFundings = new ArrayList<TransactionInputs>();
			while (result.next()) {
				offerFundings.add(new TransactionInputs(result.getString(2), result.getInt(3), result.getLong(4), result.getLong(5), result.getString(6), result.getString(7), result.getString(8), result.getString(9)));
			}
			return offerFundings;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
