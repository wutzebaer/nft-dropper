package de.peterspace.nftdropper.cardano;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.codec.DecoderException;
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

	private static final String tokenQuery = "select\r\n"
			+ "encode(ma.policy::bytea, 'hex') policyId,\r\n"
			+ "ma.name tokenName,\r\n"
			+ "mtm.quantity,\r\n"
			+ "encode(t.hash ::bytea, 'hex') txId,\r\n"
			+ "tm.json->encode(ma.policy::bytea, 'hex')->encode(ma.name::bytea, 'escape') json,\r\n"
			+ "t.invalid_before,\r\n"
			+ "t.invalid_hereafter,\r\n"
			+ "b.block_no,\r\n"
			+ "b.epoch_no,\r\n"
			+ "b.epoch_slot_no, \r\n"
			+ "t.id tid, \r\n"
			+ "mtm.id mintid,\r\n"
			+ "b.slot_no,\r\n"
			+ "(select sum(quantity) from ma_tx_mint mtm2 where mtm2.ident = mtm.ident) total_supply\r\n"
			+ "from ma_tx_mint mtm\r\n"
			+ "join tx t on t.id = mtm.tx_id \r\n"
			+ "left join tx_metadata tm on tm.tx_id = t.id \r\n"
			+ "join block b on b.id = t.block_id \r\n"
			+ "join multi_asset ma on ma.id = mtm.ident ";

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

	@lombok.Value
	public static class MintedToken {
		String assetName;
		String json;
	}

	public List<MintedToken> policyTokens(String policyId) throws DecoderException {

		try (Connection connection = hds.getConnection()) {

			String findTokenQuery = "SELECT * FROM (\r\n";
			findTokenQuery += "SELECT U.*, row_number() over(PARTITION by  policyId, tokenName order by mintid desc) rn FROM (\r\n";

			findTokenQuery += CardanoDbSyncClient.tokenQuery;
			findTokenQuery += "WHERE ";
			findTokenQuery += "encode(ma.policy::bytea, 'hex')=?";
			findTokenQuery += ") AS U where U.quantity > 0 ";
			findTokenQuery += ") as numbered ";
			findTokenQuery += "where rn = 1 ";
			findTokenQuery += "order by epoch_no, tokenname ";

			PreparedStatement getTxInput = connection.prepareStatement(findTokenQuery);
			getTxInput.setObject(1, policyId);

			ResultSet result = getTxInput.executeQuery();
			List<MintedToken> tokenDatas = parseTokenResultset(result);
			return tokenDatas;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private List<MintedToken> parseTokenResultset(ResultSet result) throws SQLException {
		List<MintedToken> tokenDatas = new ArrayList<>();
		while (result.next()) {
			tokenDatas.add(new MintedToken(new String(result.getBytes(2), StandardCharsets.UTF_8), result.getString(5)));
		}
		return tokenDatas;
	}

}
