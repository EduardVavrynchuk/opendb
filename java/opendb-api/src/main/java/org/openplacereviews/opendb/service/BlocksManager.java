package org.openplacereviews.opendb.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openplacereviews.opendb.DBConstants;
import org.openplacereviews.opendb.FailedVerificationException;
import org.openplacereviews.opendb.OUtils;
import org.openplacereviews.opendb.OpenDBServer.MetadataDb;
import org.openplacereviews.opendb.SecUtils;
import org.openplacereviews.opendb.ops.OpBlock;
import org.openplacereviews.opendb.ops.OpBlockChain;
import org.openplacereviews.opendb.ops.OpBlockchainRules;
import org.openplacereviews.opendb.ops.OpOperation;
import org.openplacereviews.opendb.service.LogOperationService.OperationStatus;
import org.openplacereviews.opendb.util.JsonFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.google.gson.JsonObject;


@Service
public class BlocksManager {
	protected static final Log LOGGER = LogFactory.getLog(BlocksManager.class);
	
	
	@Autowired
	private OperationsQueueManager queue;
	
	@Autowired
	private OperationsRegistry registry;
	
	@Autowired
	private LogOperationService logSystem;
	
	@Autowired
    private JsonFormatter formatter;
	
	@Autowired
	private JdbcTemplate jdbcTemplate;
	
	@Value("${opendb.user}")
	private String serverUser;
	@Value("${opendb.privateKey}")
	private String serverPrivateKey;
	
	private KeyPair serverKeyPair;
	
	private OpBlockChain blockchain = new OpBlockChain(null, null);
	
	private OpBlockchainRules blockchainRules = new OpBlockchainRules();
	
	private BlockchainState currentState = BlockchainState.BLOCKCHAIN_INIT;
	
	public enum BlockchainState {
		BLOCKCHAIN_INIT,
		BLOCKCHAIN_READY,
		BLOCKCHAIN_PAUSED,
		BLOCKCHAIN_IN_PROGRESS_BLOCK_PREPARE,
		BLOCKCHAIN_IN_PROGRESS_BLOCK_EXEC,
		BLOCKCHAIN_FAILED_BLOCK_EXEC
	}
	
	public BlocksManager() {
		
	}

	public String getServerPrivateKey() {
		return serverPrivateKey;
	}
	
	public String getServerUser() {
		return serverUser;
	}
	
	
	public synchronized String createBlock() {
		if (this.currentState != BlockchainState.BLOCKCHAIN_READY) {
			throw new IllegalStateException("Blockchain is not ready to create block");
		}
		try {
			OpBlock bl = new OpBlock();
			currentBlock = bl;
			List<OpOperation> candidates = bl.getOperations();
			ConcurrentLinkedQueue<OpOperation> q = queue.getOperationsQueue();
			ActiveUsersContext users = pickupOpsFromQueue(candidates, q, false);
			return executeBlock(bl, users, false);
		} catch (RuntimeException e) {
			LOGGER.error("Error creating block", e);
			if(this.currentState == BlockchainState.BLOCKCHAIN_IN_PROGRESS_BLOCK_PREPARE) {
				// this failure is not fatal and could be recovered easily 
				this.currentState = BlockchainState.BLOCKCHAIN_READY;
				this.currentBlock = null;
				return formatter.objectToJson(e);
			} else {
				this.currentState = BlockchainState.BLOCKCHAIN_FAILED_BLOCK_EXEC;
				throw e;	
			}
			
		} finally {
			if (currentState == BlockchainState.BLOCKCHAIN_IN_PROGRESS_BLOCK_EXEC) {
				this.currentState = BlockchainState.BLOCKCHAIN_READY;
				this.currentBlock = null;
			}
		}
	}
	
	public synchronized String replicateBlock(OpBlock remoteBlock) {
		if(this.currentState != BlockchainState.BLOCKCHAIN_READY) {
			throw new IllegalStateException("Blockchain is not ready to create block");
		}
		this.currentState = BlockchainState.BLOCKCHAIN_IN_PROGRESS_BLOCK_EXEC;
		try {
			currentBlock = remoteBlock;
			LinkedList<OpOperation> ops = new LinkedList<>(remoteBlock.getOperations());
			ArrayList<OpOperation> cand = new ArrayList<>();
			ActiveUsersContext users = pickupOpsFromQueue(cand, ops, true);
			if(ops.size() != cand.size()) {
				throw new RuntimeException("The block could not validate all transactions included in it");
			}
			return executeBlock(remoteBlock, users, true);
		} catch (RuntimeException e) {
			LOGGER.error("Error creating block", e);
			this.currentState = BlockchainState.BLOCKCHAIN_FAILED_BLOCK_EXEC;
			throw e;
		} finally {
			if(currentState == BlockchainState.BLOCKCHAIN_IN_PROGRESS_BLOCK_EXEC) {
				this.currentState = BlockchainState.BLOCKCHAIN_READY;
			}
		}
	}

	public synchronized void init(MetadataDb metadataDB) {
		LOGGER.info("... Blockchain. Loading blocks...");
		String msg = "";
		// db is bootstraped
		currentState = BlockchainState.BLOCKCHAIN_READY;
		LOGGER.info("+++ Blockchain is inititialized. " + msg);
	}
	
	
	public synchronized boolean resumeBlockCreation() {
		if(currentState == BlockchainState.BLOCKCHAIN_PAUSED) {
			currentState = BlockchainState.BLOCKCHAIN_READY;
			return true;
		}
		return false;
	}
	
	public synchronized boolean pauseBlockCreation() {
		if(currentState == BlockchainState.BLOCKCHAIN_READY) {
			currentState = BlockchainState.BLOCKCHAIN_PAUSED;
			return true;
		}
		return false;
	}
	
	public BlockchainState getCurrentState() {
		return currentState;
	}
	
	
	private void pickupOpsFromQueue(List<OpOperation> candidates, Collection<OpOperation> q, boolean exceptionOnFail) {
		int size = 0;
		for (OpOperation o : q) {
			int l = formatter.toJson(o).length();
			String validMsg = null;
			Exception ex = null;
			try {
				if (!blockchainRules.validateSignatures(blockchain, o)) {
					validMsg = "not verified";
				}
				if (!blockchainRules.validateHash(o)) {
					validMsg = "hash is not valid";
				}
				validMsg = blockchainRules.validateRoles(blockchain, o);
			} catch (Exception e) {
				ex = e;
			}
			if (ex != null) {
				logSystem.logOperation(OperationStatus.FAILED_PREPARE, o,
						String.format("Failed to verify operation signature: %s", validMsg), exceptionOnFail, ex);
				continue;
			}

			if (l > OpBlockchainRules.MAX_BLOCK_SIZE_MB / 2) {
				logSystem.logOperation(OperationStatus.FAILED_PREPARE, o,
						String.format("Operation discarded due to size limit %d", l), exceptionOnFail);
				continue;
			}
			if (size + l > OpBlockchainRules.MAX_BLOCK_SIZE) {
				break;
			}
			if (candidates.size() >= OpBlockchainRules.MAX_BLOCK_SIZE) {
				break;
			}
			candidates.add(o);
		}
	}

	


	private String executeBlock(OpBlock block, ActiveUsersContext users, boolean exceptionOnFail) {
		// in preparation exception could fail and it shouldn't be fatal for system 
		currentState = BlockchainState.BLOCKCHAIN_IN_PROGRESS_BLOCK_PREPARE;
		OpBlock prevBlock = getLastBlock();
		
		prepareBlockOpsToExec(block, exceptionOnFail);
		if(block.getBlockId() < 0) {
			signBlock(block, users, prevBlock);
		}
		validateBlock(block, users, prevBlock);
		
		// here we don't expect any failure or the will be fatal to the system
		currentState = BlockchainState.BLOCKCHAIN_IN_PROGRESS_BLOCK_EXEC;
		OpBlock newBlock = executeOpsInBlock(block);
		
		return formatter.toJson(newBlock);
	}

	public OpBlock getLastBlock() {
		return blockchain.getLastBlock();
	}
	
	
	private OpBlock executeOpsInBlock(OpBlock block) {
		OpBlock execBlock = new OpBlock(block);
		currentTx = null;
		List<OpOperation> ops = execBlock.getOperations();
		int indInBlock = 0;
		
		for(OpOperation o : block.getOperations()) {
			OpOperation ro = new OpOperation(o);
			JsonObject obj = formatter.toJsonObject(ro);
			currentTx = o;
			boolean execute = false;
			RuntimeException err = null;
			try {
				registry.executeOperation(ro, obj);
				// don't keep operations in memory
				ro.clearNonSignificantBlockFields();
				execute = true;
			} catch (RuntimeException e) {
				err = e;
			}
			if (!execute) {
				logSystem.logOperation(OperationStatus.FAILED_EXECUTE, o, 
						"Operations failed to execute in the accepted block", true, err);
				// not reachable code
				throw new IllegalStateException(err);
			} else {
				usersRegistry.getBlockUsers().addAuthOperation(o);
				logSystem.logOperation(OperationStatus.EXECUTED, o, "OK", false);
			}
			ops.add(ro);
			indInBlock++;
		}
		return execBlock;
	}
	
	public List<OpBlock> getBlockcchain() {
		return isBlockchainEmpty() ? Collections.emptyList() : blockchain;
	}

	private void validateBlock(OpBlock block, ActiveUsersContext users, OpBlock prevBlock) {
		if(block.getOperations().size() == 0) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block,
					"Block has no operations to execute", true);
		}
		if(!OUtils.equals(usersRegistry.calculateMerkleTreeHash(block), block.getStringValue(OpBlock.F_MERKLE_TREE_HASH))) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block,
					String.format("Failed to validate merkle tree: %s %s", usersRegistry.calculateMerkleTreeHash(block), 
							block.getStringValue(OpBlock.F_MERKLE_TREE_HASH)), true);
		}
		if(!OUtils.equals(usersRegistry.calculateSigMerkleTreeHash(block), block.getStringValue(OpBlock.F_SIG_MERKLE_TREE_HASH))) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block,
					String.format("Failed to validate signature merkle tree: %s %s", usersRegistry.calculateMerkleTreeHash(block), 
							block.getStringValue(OpBlock.F_SIG_MERKLE_TREE_HASH)), true);
		}
		if(!OUtils.equals(prevBlock.getHash(), block.getStringValue(OpBlock.F_PREV_BLOCK_HASH))) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block,
					String.format("Failed to validate previous block hash: %s %s", prevBlock.getHash(), 
							block.getStringValue(OpBlock.F_PREV_BLOCK_HASH)), true);
		}
		if(!OUtils.equals(calculateHash(block), prevBlock.getHash())) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block,
					String.format("Failed to validate block hash: %s %s", calculateHash(block), prevBlock.getHash()), true);
		}
		if(prevBlock.getBlockId() + 1 != block.getBlockId()) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block,
					String.format("Block id doesn't match with previous block id: %d %d", prevBlock.getBlockId(), block.getBlockId()), true);
		}
		boolean validateSig = true;
		Exception ex = null;
		try {
			KeyPair pk = users.getPublicLoginKeyPair(block.getStringValue(OpBlock.F_SIGNED_BY));
			byte[] blHash = SecUtils.getHashBytes(block.getHash());		
			if(pk != null && SecUtils.validateSignature(pk, blHash, block.getSignature())) {
				validateSig = true;
			} else {
				validateSig = false;
			}
		} catch (FailedVerificationException e) {
			validateSig = false;
			ex = e;
		} catch (RuntimeException e) {
			validateSig = false;
			ex = e;
		}
		if (!validateSig) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block,
					"Block signature doesn't match", true, ex);
		}
	}


	private List<OpOperation> prepareBlockOpsToExec(OpBlock block, boolean exceptionOnFail) {
		List<OpOperation> operations = new ArrayList<OpOperation>();
		Map<String, OpOperation> executedTx = new TreeMap<String, OpOperation>();
		Iterator<OpOperation> it = block.getOperations().iterator();
		while(it.hasNext()) {
			OpOperation def = it.next();
			Exception ex = null;
			boolean valid = false;
			try {
				valid = registry.preexecuteOperation(def, null);
			} catch (Exception e) {
				ex = e;
			}
			if(valid) { 
				if(executedTx.containsKey(def.getHash())) {
					logSystem.logOperation(OperationStatus.FAILED_EXECUTE, def, 
							String.format("Operations has duplicate hash in same block: %s", def.getHash()), exceptionOnFail);
					valid = false;
				}
			} else {
				logSystem.logOperation(OperationStatus.FAILED_PREPARE, def,
						"Operation couldn't be validated for execution (probably not registered) ", ex);
			}
			if(valid) {
				operations.add(def);
				executedTx.put(def.getHash(), def);
			} else {
				it.remove();
			}
		}
		return operations;
	}



	
}
