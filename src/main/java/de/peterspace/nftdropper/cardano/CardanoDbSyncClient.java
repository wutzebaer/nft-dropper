package de.peterspace.nftdropper.cardano;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;

import de.peterspace.nftdropper.TrackExecutionTime;
import de.peterspace.nftdropper.model.FullTokenData;
import de.peterspace.nftdropper.model.Hunter2SnapshotRow;
import de.peterspace.nftdropper.model.HunterSnapshotRow;
import de.peterspace.nftdropper.model.TransactionInputs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class CardanoDbSyncClient {

	private static final String hunterSnapshotQuery = "with owners as (\r\n"
			+ "	select \r\n"
			+ "	coalesce(sa.\"view\", uv.address) \"group\",\r\n"
			+ "	min(uv.address) address, \r\n"
			+ "	min(uv.stake_address_id) stake_address_id, \r\n"
			+ "	sum(mto_charly.quantity) quantity\r\n"
			+ "	from utxo_view uv \r\n"
			+ "	join tx_out to_charly on to_charly.tx_id=uv.tx_id and to_charly.\"index\"=uv.\"index\"\r\n"
			+ "	join ma_tx_out mto_charly on mto_charly.tx_out_id=to_charly.id join multi_asset ma_charly on ma_charly.id=mto_charly.ident\r\n"
			+ "	left join stake_address sa on sa.id=uv.stake_address_id\r\n"
			+ "	where \r\n"
			+ "	ma_charly.\"policy\"= decode('89267e9a35153a419e1b8ffa23e511ac39ea4e3b00452e9d500f2982', 'hex')\r\n"
			+ "	group by \"group\"\r\n"
			+ "	order by quantity desc\r\n"
			+ ")\r\n"
			+ "select owners.\"group\", min(encode(owners.address::bytea, 'escape')) address, min(encode(ma_handle.name::bytea, 'escape')) handle, min(owners.quantity) quantity\r\n"
			+ "from owners\r\n"
			+ "left join utxo_view uv_handle on uv_handle.stake_address_id=owners.stake_address_id\r\n"
			+ "left join tx_out to_handle on to_handle.tx_id=uv_handle.tx_id and to_handle.\"index\"=uv_handle.\"index\"\r\n"
			+ "left join ma_tx_out mto_handle on mto_handle.tx_out_id=to_handle.id \r\n"
			+ "left join multi_asset ma_handle on ma_handle.id=mto_handle.ident and ma_handle.\"policy\"= decode('f0ff48bbb7bbe9d59a40f1ce90e9e9d0ff5002ec48f232b49ca0fb9a', 'hex')\r\n"
			+ "group by owners.\"group\"\r\n"
			+ "order by quantity desc";

	private byte[] handlePolicyBytes;
	private static final String hunterSeason2 = """
			select
				stake_address,
				count(*),
				(select ma.name handle
					from utxo_view uv
					join tx_out to2 on to2.tx_id=uv.tx_id and to2."index"=uv."index"
					join ma_tx_out mto on mto.tx_out_id=to2.id
					join multi_asset ma on ma.id=mto.ident and ma."policy"=?
					where uv.stake_address_id=min(outputs.stake_address_id)
					limit 1)
			from (
				select distinct
					from_txo.address,
					tx.id tx_id,
					encode(tx.hash, 'hex') tx_hash,
					to_txo."index",
					to_txo.value,
					to_txo.address,
					sa."view" stake_address,
					sa.id stake_address_id
				from tx_out from_txo
				join tx_in ti on from_txo.tx_id = ti.tx_out_id AND from_txo.index = ti.tx_out_index
				join tx tx on tx.id = ti.tx_in_id
				join tx_out to_txo on to_txo.tx_id = tx.id
				join stake_address sa on sa.id=to_txo.stake_address_id
				join block b on b.id=tx.block_id
				where from_txo.address = 'addr1v9gs0trlcmyty7jakcewjs3h00a7xrzyd5wnyfrpeg4wjts0ugx63'
				and to_txo.address not in ('addr1v9gs0trlcmyty7jakcewjs3h00a7xrzyd5wnyfrpeg4wjts0ugx63', 'addr1q8yv5fqyx76s3jtrw96qgz9ply32hq84690dq6psdfazrmn2cr3agje4kp2e3crjatftkjtuugs3ftpwz8lxugwng8fq9wl02y')
				and b."time">?
				and b."time"<?
				order by tx.id desc, to_txo."index"
			) outputs
			group by stake_address
			order by count(*) desc
			""";

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

	private static final String tokenQuery2 = "select "
			+ "encode(ma.policy::bytea, 'hex') policyId, "
			+ "ma.name tokenName, "
			+ "mtm.quantity, "
			+ "encode(t.hash ::bytea, 'hex') txId, "
			+ "tm.json->encode(ma.policy::bytea, 'hex')->encode(ma.name::bytea, 'escape') json, "
			+ "t.invalid_before, "
			+ "t.invalid_hereafter, "
			+ "b.block_no, "
			+ "b.epoch_no, "
			+ "b.epoch_slot_no,  "
			+ "t.id tid,  "
			+ "mtm.id mintid, "
			+ "b.slot_no, "
			+ "(select sum(quantity) from ma_tx_mint mtm2 where mtm2.ident = mtm.ident) total_supply, "
			+ "ma.fingerprint, "
			+ "jsonb_pretty(s2.json) \"policy\" "
			+ "from ma_tx_mint mtm "
			+ "join tx t on t.id = mtm.tx_id  "
			+ "left join tx_metadata tm on tm.tx_id = t.id and tm.key=721  "
			+ "join block b on b.id = t.block_id  "
			+ "join multi_asset ma on ma.id = mtm.ident "
			+ "join script s2 on s2.hash=ma.\"policy\" ";

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

	private static final String findStakeAddressIds = "select to2.stake_address_id\r\n"
			+ "from tx_out to2 \r\n"
			+ "where \r\n"
			+ "to2.address = ANY (?)\r\n"
			+ "union\r\n"
			+ "select sa.id \r\n"
			+ "from stake_address sa \r\n"
			+ "where \r\n"
			+ "sa.\"view\" = ANY (?)";

	@Value("${cardano-db-sync.url}")
	private String url;

	@Value("${cardano-db-sync.username}")
	private String username;

	@Value("${cardano-db-sync.password}")
	private String password;

	private HikariDataSource hds;

	@PostConstruct
	public void init() throws SQLException, DecoderException {
		handlePolicyBytes = Hex.decodeHex("f0ff48bbb7bbe9d59a40f1ce90e9e9d0ff5002ec48f232b49ca0fb9a");
		hds = new HikariDataSource();
		hds.setInitializationFailTimeout(60000l);
		hds.setJdbcUrl(url);
		hds.setUsername(username);
		hds.setPassword(password);
		hds.setMaximumPoolSize(5);
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
			findTokenQuery += "where rn = 1 and total_supply > 0 ";
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

	public List<HunterSnapshotRow> createHunterSnapshot() {
		try (Connection connection = hds.getConnection()) {
			PreparedStatement hunterSnapshotStatement = connection.prepareStatement(hunterSnapshotQuery);
			ResultSet resultSet = hunterSnapshotStatement.executeQuery();

			List<HunterSnapshotRow> rows = new ArrayList<>();

			while (resultSet.next()) {
				HunterSnapshotRow hunterSnapshotRow = new HunterSnapshotRow();
				hunterSnapshotRow.setGroup(resultSet.getString(1));
				hunterSnapshotRow.setAddress(resultSet.getString(2));
				hunterSnapshotRow.setHandle(resultSet.getString(3));
				hunterSnapshotRow.setQuantity(resultSet.getLong(4));
				rows.add(hunterSnapshotRow);
			}

			return rows;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public List<Hunter2SnapshotRow> createHunter2Snapshot(Instant start, Instant end) {
		try (Connection connection = hds.getConnection()) {
			PreparedStatement hunterSnapshotStatement = connection.prepareStatement(hunterSeason2);
			hunterSnapshotStatement.setBytes(1, handlePolicyBytes);
			hunterSnapshotStatement.setTimestamp(2, Timestamp.from(start));
			hunterSnapshotStatement.setTimestamp(3, Timestamp.from(end));

			ResultSet resultSet = hunterSnapshotStatement.executeQuery();

			List<Hunter2SnapshotRow> rows = new ArrayList<>();

			while (resultSet.next()) {
				Hunter2SnapshotRow hunterSnapshotRow = new Hunter2SnapshotRow();
				hunterSnapshotRow.setStakeAddress(resultSet.getString("stake_address"));
				hunterSnapshotRow.setCount(resultSet.getLong("count"));
				hunterSnapshotRow.setHandle(new String(resultSet.getBytes("handle"), StandardCharsets.UTF_8));
				rows.add(hunterSnapshotRow);
			}

			return rows;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public Set<Long> findStakeAddressIds(String[] address) throws DecoderException {
		try (Connection connection = hds.getConnection()) {
			PreparedStatement getTxInput = connection.prepareStatement(findStakeAddressIds);
			Array addressArray = connection.createArrayOf("VARCHAR", address);
			getTxInput.setArray(1, addressArray);
			getTxInput.setArray(2, addressArray);
			ResultSet result = getTxInput.executeQuery();

			Set<Long> stakeAddressIds = new HashSet<>();
			while (result.next()) {
				stakeAddressIds.add(result.getLong(1));
			}
			return stakeAddressIds;

		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@TrackExecutionTime
	@Cacheable("findTokens")
	public List<FullTokenData> findTokens(String string, Long fromMintid) throws DecoderException {

		log.info("findTokens");

		try (Connection connection = hds.getConnection()) {

			String findTokenQuery = "SELECT * FROM ( ";
			findTokenQuery += "SELECT U.*, row_number() over(PARTITION by  policyId, tokenName order by mintid desc) rn FROM ( ";

			Map<Integer, Object> fillPlaceholders = new HashMap<>();

			String[] bits = string.split("\\.");
			if (bits.length == 2 && bits[0].length() == 56) {
				findTokenQuery += CardanoDbSyncClient.tokenQuery2;
				findTokenQuery += "WHERE ";
				findTokenQuery += "ma.policy=? AND ma.name=? ";

				fillPlaceholders.put(1, Hex.decodeHex(bits[0]));
				fillPlaceholders.put(2, bits[1].getBytes(StandardCharsets.UTF_8));
				if (fromMintid != null)
					fillPlaceholders.put(3, fromMintid);

			} else if (bits.length == 1 && bits[0].length() == 56) {
				findTokenQuery += CardanoDbSyncClient.tokenQuery2;
				findTokenQuery += "WHERE ";
				findTokenQuery += "ma.policy=?";

				fillPlaceholders.put(1, Hex.decodeHex(bits[0]));
				if (fromMintid != null)
					fillPlaceholders.put(2, fromMintid);

			} else if (string.length() == 44 && string.startsWith("asset")) {
				System.err.println("");
				findTokenQuery += CardanoDbSyncClient.tokenQuery2;
				findTokenQuery += "WHERE ";
				findTokenQuery += "ma.fingerprint=?";
				fillPlaceholders.put(1, string);
				if (fromMintid != null)
					fillPlaceholders.put(2, fromMintid);
			} else {
				findTokenQuery += CardanoDbSyncClient.tokenQuery2;
				findTokenQuery += "WHERE ";
				findTokenQuery += "to_tsvector('english',json) @@ to_tsquery(?) ";
				findTokenQuery += "and to_tsvector('english',tm.json->encode(ma.policy::bytea, 'hex')->convert_from(ma.name, 'UTF8')) @@ to_tsquery(?) ";

				String tsquery = string.trim().replaceAll("[^A-Za-z0-9]+", " & ");
				fillPlaceholders.put(1, tsquery);
				fillPlaceholders.put(2, tsquery);
				if (fromMintid != null)
					fillPlaceholders.put(3, fromMintid);
			}

			findTokenQuery += ") AS U where U.quantity > 0 ";
			findTokenQuery += ") as numbered ";

			findTokenQuery += "where rn = 1 ";
			if (fromMintid != null)
				findTokenQuery += "and mintid > ? ";

			findTokenQuery += "order by epoch_no, tokenname ";
			findTokenQuery += "limit 1000 ";

			PreparedStatement getTxInput = connection.prepareStatement(findTokenQuery);
			for (Entry<Integer, Object> entry : fillPlaceholders.entrySet()) {
				getTxInput.setObject(entry.getKey(), entry.getValue());
			}

			ResultSet result = getTxInput.executeQuery();
			List<FullTokenData> tokenDatas = parseTokenResultset2(result);
			return tokenDatas;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private List<FullTokenData> parseTokenResultset2(ResultSet result) throws SQLException {
		List<FullTokenData> tokenDatas = new ArrayList<>();
		while (result.next()) {

			FullTokenData tokenData = new FullTokenData();
			tokenData.setPolicyId(result.getString(1));
			tokenData.setName(new String(result.getBytes(2), StandardCharsets.UTF_8));
			tokenData.setQuantity(result.getLong(3));
			tokenData.setTxId(result.getString(4));
			tokenData.setJson(result.getString(5));

			if (!StringUtils.isBlank(tokenData.getJson())) {
				tokenData.setMetadata(new JSONObject(tokenData.getJson()));
			} else {
				continue;
			}

			tokenData.setInvalid_before(result.getLong(6));
			if (result.wasNull()) {
				tokenData.setInvalid_before(null);
			}
			tokenData.setInvalid_hereafter(result.getLong(7));
			if (result.wasNull()) {
				tokenData.setInvalid_hereafter(null);
			}
			tokenData.setBlockNo(result.getLong(8));
			tokenData.setEpochNo(result.getLong(9));
			tokenData.setEpochSlotNo(result.getLong(10));
			tokenData.setTid(result.getLong(11));
			tokenData.setMintid(result.getLong(12));
			tokenData.setSlotNo(result.getLong(13));
			tokenData.setTotalSupply(result.getLong(14));
			tokenData.setFingerprint(result.getString(15));
			tokenDatas.add(tokenData);

			tokenData.setPolicy(result.getString(16));
		}

		return tokenDatas;
	}

	private List<MintedToken> parseTokenResultset(ResultSet result) throws SQLException {
		List<MintedToken> tokenDatas = new ArrayList<>();
		while (result.next()) {
			tokenDatas.add(new MintedToken(new String(result.getBytes(2), StandardCharsets.UTF_8), result.getString(5)));
		}
		return tokenDatas;
	}

}
