package org.openplacereviews.opendb.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openplacereviews.opendb.OUtils;
import org.openplacereviews.opendb.OpenDBServer.MetadataDb;
import org.openplacereviews.opendb.SecUtils;
import org.openplacereviews.opendb.ops.OpBlock;
import org.openplacereviews.opendb.ops.OpBlockChain;
import org.openplacereviews.opendb.ops.OpBlockchainRules;
import org.openplacereviews.opendb.ops.OpOperation;
import org.openplacereviews.opendb.util.JsonFormatter;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import wiremock.org.eclipse.jetty.util.ConcurrentHashSet;

@Service
public class DBConsensusManager {
	protected static final Log LOGGER = LogFactory.getLog(DBConsensusManager.class);
	
	private static final String FIELD_NAME = "name";
	
	// check SimulateSuperblockCompactSequences to verify numbers
	private static final double COMPACT_COEF = 0.5;
	protected static final int COMPACT_ITERATIONS = 3;
		
	@Autowired
	private JdbcTemplate jdbcTemplate;
	
	@Autowired
	private JsonFormatter formatter;
	
	@Autowired
	private LogOperationService logSystem;

	private Map<String, BlockInfo> blocks = new ConcurrentHashMap<String, BlockInfo>();
	private Map<String, BlockInfo> orphanedBlocks = new ConcurrentHashMap<String, BlockInfo>();
	private Superblock mainSavedChain = null;
	
	public static class BlockInfo {
		public final String hash;
		public final BlockInfo parent;
		public final int depth;
		public final Deque<BlockInfo> children = new ConcurrentLinkedDeque<BlockInfo>();
		public BlockInfo(String hash, BlockInfo parent, int depth) {
			this.hash = hash;
			this.parent = parent;
			this.depth = depth;
		}
		public boolean containsBlock(String block) {
			if(hash.equals(block)) {
				return true;
			} else if(parent != null) {
				return parent.containsBlock(block);
			}
			return false;
		}
		
	}
	
	public static class Superblock {
		public Superblock parent;
		public final String superblockHash;
		
		final Set<String> blocksSet = new ConcurrentHashSet<String>();
		final Deque<String> blocks = new ConcurrentLinkedDeque<String>();
		
		public Superblock(String superBlockHash, Superblock parent) {
			this.parent = parent;
			this.superblockHash = superBlockHash;
		}
		
		public boolean isLeaf() {
			return blocks.size() > 0 && superblockHash.isEmpty();
		}
		
		public int getDepth() {
			int d = blocks.size();
			if(parent != null) {
				d += parent.getDepth();
			}
			return d;
		}
		
		public String calculateRawSuperBlockHash(OpBlockchainRules rules) {
			if(blocks.size() == 0) {
				return "";
			}
			return rules.calculateSuperblockHash(blocks.size(), blocks.peekLast());
		}

		public boolean containsBlock(String block) {
			if(blocksSet.contains(block)) {
				return true;
			}
			if(parent != null) {
				return parent.containsBlock(block);
			}
			return false;
		}

		public List<Superblock> getSuperblocksFromFirst(List<Superblock> l) {
			if (parent == null) {
				if (l == null) {
					l = new ArrayList<Superblock>();
				}
			} else {
				l = parent.getSuperblocksFromFirst(l);
			}
			l.add(this);
			return l;
		}
	}
	
	
	//////////// SYSTEM TABLES DDL ////////////
	protected static final String DDL_CREATE_TABLE_BLOCKS = "create table blocks (hash bytea PRIMARY KEY, phash bytea, blockid int, superblock bytea, details jsonb)";
	protected static final String DDL_CREATE_TABLE_BLOCK_INDEX_HASH = "create index blocks_hash_ind on blocks(hash)";
	protected static final String DDL_CREATE_TABLE_BLOCK_INDEX_PHASH = "create index blocks_phash_ind on blocks(phash)";
	protected static final String DDL_CREATE_TABLE_BLOCK_INDEX_SUPERBLOCK = "create index blocks_superblock_ind on blocks(superblock)";
	protected static final String DDL_CREATE_TABLE_BLOCK_INDEX_BLOCKID = "create index blocks_blockid_ind on blocks(blockid)";
	
	
	protected static final String DDL_CREATE_TABLE_OPS = "create table operations (dbid serial not null, hash bytea PRIMARY KEY, blocks bytea[], details jsonb)";
	protected static final String DDL_CREATE_TABLE_OPS_INDEX_HASH = "create index operations_hash_ind on operations(hash)";

	// leaf superblock is not stored in db
	private static final String LEAF_SUPERBLOCK_ID = "";
	
	// Query / insert values 
	// select encode(b::bytea, 'hex') from test where b like (E'\\x39')::bytea||'%';
	// insert into test(b) values (decode('39556d070fd95f54b554010207d42605a8d0adfbb3b8b8e134df7df0689d78ab', 'hex'));
	// UPDATE blocks SET superblocks = array_remove(superblocks, decode('39556d070fd95f54b554010207d42605a8d0adfbb3b8b8e134df7df0689d78ab', 'hex'));

	public static void main(String[] args) {
		System.out.println(DDL_CREATE_TABLE_BLOCKS + ';');
		System.out.println(DDL_CREATE_TABLE_BLOCK_INDEX_HASH + ';');
		System.out.println(DDL_CREATE_TABLE_BLOCK_INDEX_PHASH + ';');
		System.out.println(DDL_CREATE_TABLE_BLOCK_INDEX_SUPERBLOCK + ';');
		System.out.println(DDL_CREATE_TABLE_BLOCK_INDEX_BLOCKID + ';');
		
		System.out.println(DDL_CREATE_TABLE_OPS + ';');
		System.out.println(DDL_CREATE_TABLE_OPS_INDEX_HASH + ';');
	}
	
	
	public Map<String, BlockInfo> getOrphanedBlocks() {
		return orphanedBlocks;
	}

	protected String getHexFromPgObject(PGobject o) {
		String s = o.getValue();
		if(s == null) {
			return "";
		}
		if(!s.startsWith("\\x")) {
			throw new UnsupportedOperationException();
		}
		return s.substring(2);
	}
	
	public String getSuperblockHash() {
		Superblock s = mainSavedChain;
		return getSuperblockHash(s);
	}


	private String getSuperblockHash(Superblock s) {
		return s == null ? "" : s.superblockHash;
	}
	
	public String getLastBlockHash() {
		Superblock s = mainSavedChain;
		return getLastBlockHash(s);
	}


	private String getLastBlockHash(Superblock s) {
		return s == null ? "" : s.blocks.peekLast();
	}
	
	public int getDepth() {
		Superblock s = mainSavedChain;
		return getDepth(s);
	}


	private int getDepth(Superblock s) {
		return s == null ? 0 : s.getDepth();
	}
	
	// mainchain could change
	public synchronized OpBlockChain init(MetadataDb metadataDB) {
		final OpBlockchainRules rules = new OpBlockchainRules(formatter, logSystem);
		LOGGER.info("... Loading block headers ...");
		mainSavedChain = loadBlockHeadersAndBuildMainChain();
		
		LOGGER.info(String.format("+++ Loaded %d block headers +++", blocks.size()));
		
		BlockInfo topBlockInfo = selectTopBlockFromOrphanedBlcoks();
		Superblock topChain = putBlocksOnTopOfchain(topBlockInfo, mainSavedChain);
		
		LOGGER.info(String.format("### Selected main blockchain with '%s' and %d depth. Orphaned blocks %d. ###", 
				getLastBlockHash(topChain), getDepth(topChain), orphanedBlocks.size()));
		LOGGER.info("... Loading blocks from database ...");
		
		OpBlockChain parent = loadBlockchain(rules, topChain);
		LOGGER.info(String.format("### Loaded %d blocks ###", parent.getDepth()));
		
		OpBlockChain blcQueue = new OpBlockChain(parent, rules);
		
		LOGGER.info("... Loading operation queue  ...");
		int[] ops = new int[1];
		jdbcTemplate.query("SELECT details from operations where blocks is null order by dbid asc ", new RowCallbackHandler(){

			@Override
			public void processRow(ResultSet rs) throws SQLException {
				ops[0]++;
				OpOperation op = formatter.parseOperation(rs.getString(1));
				op.makeImmutable();
				blcQueue.addOperation(op);
			}
			
		});
		LOGGER.info(String.format("... Loaded operation %d into queue  ...", ops[0]));
		LOGGER.info(String.format("+++ Database blockchain initialized +++"));
		return blcQueue;
	}



	private OpBlockChain loadBlockchain(final OpBlockchainRules rules, Superblock chain) {
		OpBlockChain parent = OpBlockChain.NULL;
		if (chain != null) {
			List<Superblock> allChains = chain.getSuperblocksFromFirst(null);
			for (Superblock sc : allChains) {
				parent = new OpBlockChain(parent, rules);
				loadBlocks(sc, parent);
			}
		}
		return parent;
	}
	
	private void loadBlocks(Superblock sc, final OpBlockChain newParent) {
		Iterator<String> ds = sc.blocks.iterator();
		while (ds.hasNext()) {
			String blockHash = ds.next();
			jdbcTemplate.query("SELECT details from blocks where hash = ? ",
					new Object[] { SecUtils.getHashBytes(blockHash) }, new RowCallbackHandler() {

						@Override
						public void processRow(ResultSet rs) throws SQLException {
							OpBlock rawBlock = formatter.parseBlock(rs.getString(1));
							rawBlock.makeImmutable();
							OpBlock replicateBlock = newParent.replicateBlock(rawBlock);
							if (replicateBlock == null) {
								throw new IllegalStateException("Could not replicate block: "
										+ formatter.toJson(rawBlock));
							}
						}
					});
		}
	}

	private BlockInfo createBlockInfo(String blockHash, String pblockHash, int blockid) {
		BlockInfo parent = blocks.get(pblockHash);
		if(!OUtils.isEmpty(pblockHash) && parent == null) {
			LOGGER.error(String.format("Orphaned block '%s' without parent '%s'.", blockHash, pblockHash ));
			return null;
		}
		BlockInfo blockInfo = new BlockInfo(blockHash, parent, blockid);
		if(parent != null) {
			parent.children.addLast(blockInfo);
		}
		blocks.put(blockHash, blockInfo);
		return blockInfo;
	}

	private Superblock loadBlockHeadersAndBuildMainChain() {
		Superblock[] res = new Superblock[1];
		jdbcTemplate.query("SELECT hash, phash, blockid, superblock from blocks order by blockId asc", new RowCallbackHandler() {
			@Override
			public void processRow(ResultSet rs) throws SQLException {
				String blockHash = SecUtils.hexify(rs.getBytes(1));
				String pblockHash = SecUtils.hexify(rs.getBytes(2));
				String superblock = SecUtils.hexify(rs.getBytes(4));
				BlockInfo blockInfo = createBlockInfo(blockHash, pblockHash, rs.getInt(3));
				if(blockInfo == null) {
					return;
				}
				if(OUtils.isEmpty(superblock)) {
					orphanedBlocks.put(blockHash, blockInfo);
				} else {
					String hsh = getSuperblockHash(res[0]);
					if(OUtils.equals(hsh, superblock)) {
						// reuse mainchain
					} else if (OUtils.equals(pblockHash, getLastBlockHash(res[0]))){
						res[0] = new Superblock(superblock, res[0]);
					} else {
						throw new IllegalStateException(
								String.format("Block '%s'. Illegal parent '%s' for superblock '%s'", blockHash, superblock));
					}
					res[0].blocks.addLast(blockHash);
					res[0].blocksSet.add(blockHash);
				}
			}

			
		});
		return res[0];
	}



	private BlockInfo selectTopBlockFromOrphanedBlcoks() {
		BlockInfo topBlockInfo = null;
		for(BlockInfo bi : orphanedBlocks.values()) {
			if(topBlockInfo == null || topBlockInfo.depth < bi.depth ||
					(topBlockInfo.depth == bi.depth && topBlockInfo.hash.compareTo(bi.hash) > 0)){
				topBlockInfo = bi;
			}
		}
		return topBlockInfo;
	}



	private Superblock putBlocksOnTopOfchain(BlockInfo topBlockInfo, Superblock chain) {
		String lastBlockOfMainChain = chain == null ? "" : chain.blocks.peekLast();
		if(topBlockInfo != null && chain != null && !topBlockInfo.containsBlock(lastBlockOfMainChain)) {
			throw new IllegalStateException(String.format("Top selected block '%s' doesn't contain last block '%s' from superblock", topBlockInfo.hash, lastBlockOfMainChain));
		}
		Superblock res = chain;
		if(topBlockInfo != null && !OUtils.equals(topBlockInfo.hash, lastBlockOfMainChain)) {
			BlockInfo bi = topBlockInfo;
			res = new Superblock(LEAF_SUPERBLOCK_ID, chain);
			while(!lastBlockOfMainChain.equals(bi == null ? "": bi.hash)) {
				res.blocks.addFirst(bi.hash);
				res.blocksSet.add(bi.hash);
				orphanedBlocks.remove(bi.hash);
				bi = bi.parent; 
				// bi couldn't be null;
			}
		}
		return res;
	}


	public void insertBlock(OpBlock opBlock) {
		PGobject pGobject = new PGobject();
		pGobject.setType("jsonb");
		try {
			pGobject.setValue(formatter.toJson(opBlock));
		} catch (SQLException e) {
			throw new IllegalArgumentException(e);
		}
		byte[] blockHash = SecUtils.getHashBytes(opBlock.getHash());
		String rawHash = SecUtils.hexify(blockHash);
		byte[] prevBlockHash = SecUtils.getHashBytes(opBlock.getStringValue(OpBlock.F_PREV_BLOCK_HASH));
		String rawPrevBlockHash = SecUtils.hexify(prevBlockHash);
		jdbcTemplate.update("INSERT INTO blocks(hash, phash, blockid, details) VALUES (?, ?, ?, ?)" , 
				blockHash, prevBlockHash, opBlock.getBlockId(), pGobject);
		for(OpOperation o : opBlock.getOperations()) {
			jdbcTemplate.update("UPDATE operations set blocks = blocks || ? where hash = ?" , 
					blockHash, SecUtils.getHashBytes(o.getHash()));	
		}
		BlockInfo bi = createBlockInfo(rawHash, rawPrevBlockHash, opBlock.getBlockId());
		if(bi != null) {
			orphanedBlocks.put(rawHash, bi);
		}
	}
	
	
	public synchronized void saveMainBlockchain(OpBlockChain blc) {
		// find saved part of chain
		Deque<OpBlockChain> notSaved = new LinkedList<OpBlockChain>();
		OpBlockChain p = blc;
		String lastBlockHash = getSuperblockHash(mainSavedChain);
		while(p != null && !p.getSuperBlockHash().equals(lastBlockHash)) {
			notSaved.addFirst(p);
			p = p.getParent();
		}
		if(p == null) {
			printBlockChain(blc);
			throw new IllegalStateException("Runtime blockchain doesn't match db blockchain:" + getSuperblockHash(mainSavedChain));
		} 
		for(OpBlockChain ns : notSaved) {
			mainSavedChain = saveSuperblock(ns, mainSavedChain);
		}
	}
	
	private Superblock saveSuperblock(OpBlockChain blc, Superblock parent) {
		String superBlockHash = blc.getSuperBlockHash();
		LOGGER.info(String.format("Save superblock %s ", superBlockHash));
		if(!blc.getParent().getSuperBlockHash().equals(getSuperblockHash(parent))) {
			throw new IllegalStateException(
					String.format("DB-blockchain hash '%s' != '%s' in-memory blockchain",
							blc.getParent().getSuperBlockHash(), getSuperblockHash(parent)));
		}
		Superblock sc = new Superblock(superBlockHash, parent);
		byte[] shash = SecUtils.getHashBytes(superBlockHash);
		byte[] phash = SecUtils.getHashBytes(getSuperblockHash(parent));
		
		byte[] empty = new byte[0];
		Iterator<OpBlock> it = blc.getOneSuperBlock().iterator();
		while (it.hasNext()) {
			OpBlock o = it.next();
			byte[] blHash = SecUtils.getHashBytes(o.getHash());
			String blockRawHash = SecUtils.hexify(blHash);
			// assign parent hash only for last block
			// LOGGER.info(String.format("Update block %s to superblock %s ", o.getHash(), superBlockHash));
			jdbcTemplate.update("UPDATE blocks set superblock = ?, psuperblock =  ? where hash = ?",
							shash, it.hasNext() ? empty : phash, blHash);
			sc.blocks.addFirst(blockRawHash);
			sc.blocksSet.add(blockRawHash);
		}
		return sc;
	}
	
	public synchronized void compact(OpBlockChain blc) {
		OpBlockChain p = blc;
		String lastBlockHash = getSuperblockHash(mainSavedChain);
		while(p != null && !p.getSuperBlockHash().equals(lastBlockHash)) {
			p = p.getParent();
		}
		if (p != null) {
			for (int i = 0; i < COMPACT_ITERATIONS; i++) {
				Superblock compacted = compact(p, mainSavedChain);
				if (compacted != null) {
					mainSavedChain = compacted;
				} else {
					break;
				}
			}
			printBlockChain(blc);
		}
	}

	private Superblock compact(OpBlockChain runtimeChain, Superblock sc) {
		// nothing to compact
		if (runtimeChain == null || runtimeChain.isNullBlock() || runtimeChain.getParent().isNullBlock()) {
			return null;
		}
		if(!runtimeChain.getSuperBlockHash().equals(getSuperblockHash(sc)) || 
				!runtimeChain.getParent().getSuperBlockHash().equals(getSuperblockHash(sc.parent))) {
			LOGGER.error(String.format("ERROR situation with compacting '%s' = '%s' and '%s' = '%s' ", 
					runtimeChain.getSuperBlockHash(), getSuperblockHash(sc), 
					runtimeChain.getParent().getSuperBlockHash(), getSuperblockHash(sc.parent)));
			return null;
		}
		boolean lastTwoBlocks = runtimeChain.getParent().getParent().isNullBlock();
		Superblock compacted = compact(runtimeChain.getParent(), sc.parent);
		if(compacted == null) {
			boolean compactCondition = !lastTwoBlocks && COMPACT_COEF * (runtimeChain.getSuperblockSize()  + runtimeChain.getParent().getSuperblockSize()) >= 
					runtimeChain.getParent().getParent().getSuperblockSize();
			compactCondition = compactCondition || (runtimeChain.getSuperblockSize() > runtimeChain.getParent().getSuperblockSize());
			if(compactCondition) { 
				Superblock oldParent = sc.parent;
				if(runtimeChain.mergeWithParent()) {
					LOGGER.info(String.format("Compact superblock '%s' into  superblock '%s' ", oldParent.superblockHash, sc.superblockHash));
					return saveSuperblock(runtimeChain, oldParent.parent);
				}
			}
			return null;
		} else {
			// update parent (parent content didn't change only block sequence changed )
			if(sc.parent != compacted) {
				sc.parent = compacted;
			}
			return sc;
		}
	}



	private void printBlockChain(OpBlockChain blc) {
		List<String> superBlocksChain = new ArrayList<String>();
		OpBlockChain p = blc;
		while(p != null) {
			String sh = p.getSuperBlockHash();
			if(sh.length() > 10) {
				superBlocksChain.add(sh.substring(0, 10));
			}
			p = p.getParent();
		}
		LOGGER.info(String.format("Runtime chain %s", superBlocksChain));
		
		superBlocksChain.clear();
		Superblock s = mainSavedChain;
		while(s != null) {
			String sh = s.superblockHash;
			if(sh.length() > 10) {
				superBlocksChain.add(sh.substring(0, 10));
			}
			s = s.parent;
		}
		LOGGER.info(String.format("DB chain %s", superBlocksChain));
	}
		
		
	
	public void insertOperation(OpOperation op) {
		PGobject pGobject = new PGobject();
		pGobject.setType("jsonb");
		try {
			pGobject.setValue(formatter.opToJson(op));
		} catch (SQLException e) {
			throw new IllegalArgumentException(e);
		}
		jdbcTemplate.update("INSERT INTO operations(hash, details) VALUES (?, ?)" , 
				SecUtils.getHashBytes(op.getHash()), pGobject);
	}
	

	protected boolean createTable(OpOperation definition) {
		String tableName = definition.getStringValue(FIELD_NAME);
		Map<String, String> tableColumns = new TreeMap<String, String>();
		StringBuilder sql = new StringBuilder("create table " + tableName);
		StringBuilder columnsDef = new StringBuilder();
		for(Entry<String, String> e : tableColumns.entrySet()) {
			if(columnsDef.length() > 0) {
				columnsDef.append(", ");
			}
			columnsDef.append(e.getKey()).append(" ").append(e.getValue());
		}
		sql.append("(").append(columnsDef).append(")");
		try {
			LOGGER.info("DDL executed: " + sql);
			jdbcTemplate.execute(sql.toString());
		} catch(RuntimeException e) {
			LOGGER.warn("DDL failed: " + e.getMessage(), e);
			throw e;
		}
		return true;
	}




}
