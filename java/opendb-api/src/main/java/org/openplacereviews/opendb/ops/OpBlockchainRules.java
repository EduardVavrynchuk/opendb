package org.openplacereviews.opendb.ops;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openplacereviews.opendb.FailedVerificationException;
import org.openplacereviews.opendb.OUtils;
import org.openplacereviews.opendb.OpenDBServer;
import org.openplacereviews.opendb.SecUtils;
import org.openplacereviews.opendb.ops.OpBlockChain.ErrorType;
import org.openplacereviews.opendb.service.ActiveUsersContext;
import org.openplacereviews.opendb.service.OperationsRegistry;
import org.openplacereviews.opendb.service.LogOperationService.OperationStatus;
import org.openplacereviews.opendb.util.JsonFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * State less blockchain rules to validate roles and calculate hashes
 */
public class OpBlockchainRules {

	protected static final Log LOGGER = LogFactory.getLog(OpenDBServer.class);
	
	public static final int MAX_BLOCK_SIZE = 1000;
	public static final int MAX_BLOCK_SIZE_MB = 1 << 20;
	public static final int BLOCK_VERSION = 1;
	// not used by this implementation
	private static final String BLOCK_CREATION_DETAILS = "";
	private static final long BLOCK_EXTRA = 0;
	
	

	public static final String F_DIGEST = "digest"; // signature
	public static final String F_ALGO = "algo"; // login, signup, signature
	public static final String F_PUBKEY = "pubkey"; // login, signup
	public static final String F_NAME = "name"; // login, signup - name, login has with purpose like 'name:purpose',
	public static final String F_SALT = "salt"; // signup (salt used for pwd or oauthid_hash)
	public static final String F_AUTH_METHOD = "auth_method"; // signup - pwd, oauth, provided
	public static final String F_OAUTH_PROVIDER = "oauth_provider"; // signup
	public static final String F_OAUTHID_HASH = "oauthid_hash"; // hash with salt of the oauth_id
	public static final String F_KEYGEN_METHOD = "keygen_method"; // optional login, signup (for pwd important)
	public static final String F_DETAILS = "details"; // signup

	// transient - not stored in blockchain
	public static final String F_PRIVATEKEY = "privatekey"; // private key to return to user
	public static final String F_UID = "uid"; // user identifier

	public static final String METHOD_OAUTH = "oauth";
	public static final String METHOD_PWD = "pwd";
	public static final String METHOD_PROVIDED = "provided";

	public static final String JSON_MSG_TYPE = "json";

	// this char is not allowed in the nickname!
	public static final char USER_LOGIN_CHAR = ':';

	@Autowired
	private JsonFormatter formatter;

	@Autowired
	private JdbcTemplate jdbcTemplate;
	
	public String getSignupDescription() {
		return "This operation signs up new user in DB."
				+ "<br>This operation must be signed by signup key itself and the login key of the server that can signup users."
				+ "<br>Supported fields:" + "<br>'name' : unique nickname"
				+ "<br>'auth_method' : authorization method (oauth, pwd, provided)"
				+ "<br>'pub_key' : public key for assymetric crypthograph"
				+ "<br>'algo' : algorithm for assymetric crypthograph"
				+ "<br>'keygen_method' : keygen is specified when pwd is used"
				+ "<br>'oauthid_hash' : hash for oauth id which is calculated with 'salt'"
				+ "<br>'oauth_provider' : oauth provider such as osm, fb, google"
				+ "<br>'details' : json with details for spoken languages, avatar, country"
				+ "<br>list of other fields";
	}

	public String getLoginDescription() {
		return "This operation logins an existing user to a specific 'site' (named login). "
				+ "In case user was logged in under such name the previous login key pair will become invalid."
				+ "<br>This operation must be signed by signup key of the user." + "<br>Supported fields:"
				+ "<br>'name' : unique name for a user of the site or purpose of login"
				+ "<br>'pub_key' : public key for assymetric crypthograph"
				+ "<br>'algo' : algorithm for assymetric crypthograph"
				+ "<br>'keygen_method' : later could be used to explain how the key was calculated"
				+ "<br>'details' : json with details" + "<br>list of other fields";
	}

	private static boolean isAllowedNicknameSymbol(char c) {
		return c == ' ' || c == '$' || c == '_' || c == '.' || c == '-';
	}

	public static boolean validateNickname(String name) {
		if (name.trim().length() == 0) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (!Character.isLetter(c) && !Character.isDigit(c) && !isAllowedNicknameSymbol(c)) {
				return false;
			}
		}
		return true;
	}

	public static String getSiteFromUser(String name) {
		int i = name.indexOf(USER_LOGIN_CHAR);
		return i >= 0 ? name.substring(i + 1) : "";
	}

	public static String getNicknameFromUser(String name) {
		int i = name.indexOf(USER_LOGIN_CHAR);
		return i >= 0 ? name.substring(0, i) : name;
	}

	public static String getUserFromNicknameAndSite(String nickname, String site) {
		return nickname + USER_LOGIN_CHAR + site;
	}

	public String calculateMerkleTreeHash(OpBlock op) {
		List<byte[]> hashes = new ArrayList<byte[]>();
		for (OpOperation o : op.getOperations()) {
			byte[] hashBytes = SecUtils.getHashBytes(o.getHash());
			hashes.add(hashBytes);
		}
		return calculateMerkleTreeInPlaceHash(SecUtils.HASH_SHA256, hashes);
	}

	public String calculateSigMerkleTreeHash(OpBlock op) {
		List<byte[]> hashes = new ArrayList<byte[]>();
		for (OpOperation o : op.getOperations()) {
			List<String> sigs = o.getSignatureList();
			byte[] bts = null;
			for (String s : sigs) {
				bts = SecUtils.mergeTwoArrays(bts, SecUtils.decodeSignature(s));
			}
			hashes.add(bts);
		}
		return calculateMerkleTreeInPlaceHash(SecUtils.HASH_SHA256, hashes);
	}

	private String calculateMerkleTreeInPlaceHash(String algo, List<byte[]> hashes) {
		if (hashes.size() == 0) {
			return "";
		}
		if (hashes.size() <= 1) {
			return SecUtils.formatHashWithAlgo(algo, hashes.get(0));
		}
		List<byte[]> nextLevel = new ArrayList<byte[]>();
		for (int i = 0; i < hashes.size(); i += 2) {
			byte[] hsh = SecUtils.calculateHash(algo, hashes.get(i),
					i + 1 < hashes.size() ? hashes.get(i + 1) : hashes.get(i));
			nextLevel.add(hsh);
		}
		return calculateMerkleTreeInPlaceHash(algo, nextLevel);
	}

	// hash and signature operations
	public String calculateOperationHash(OpOperation ob, boolean set) {
		String oldHash = (String) ob.remove(OpOperation.F_HASH);
		Object sig = ob.remove(OpOperation.F_SIGNATURE);
		String hash = JSON_MSG_TYPE + ":"
				+ SecUtils.calculateHashWithAlgo(SecUtils.HASH_SHA256, null, formatter.toJson(ob));
		if (set) {
			ob.putStringValue(OpOperation.F_HASH, hash);
		} else {
			ob.putStringValue(OpOperation.F_HASH, oldHash);
		}
		ob.putObjectValue(OpOperation.F_SIGNATURE, sig);
		return hash;
	}

	public OpOperation generateHashAndSign(OpOperation op, KeyPair... keyPair) throws FailedVerificationException {
		String hsh = calculateOperationHash(op, true);
		byte[] hashBytes = SecUtils.getHashBytes(hsh);
		op.remove(OpOperation.F_SIGNATURE);
		for (KeyPair o : keyPair) {
			String sig = SecUtils.signMessageWithKeyBase64(o, hashBytes, SecUtils.SIG_ALGO_ECDSA, null);
			op.addOrSetStringValue(OpOperation.F_SIGNATURE, sig);
		}
		return op;
	}

	
	public String validateRoles(OpBlockChain blockchain, OpOperation o) {
		// TODO Auto-generated method stub
		return null;
	}
	
	public boolean validateBlock(OpBlockChain blockChain, OpBlock block, OpBlock prevBlock) {
		String blockHash = block.getHash();
		int blockId = block.getBlockId();
		int pid = -1;
		if(prevBlock != null) {
			if(!OUtils.equals(prevBlock.getHash(), block.getStringValue(OpBlock.F_PREV_BLOCK_HASH))) {
				return error(ErrorType.PREV_BLOCK_HASH, prevBlock.getHash(), 
						block.getStringValue(OpBlock.F_PREV_BLOCK_HASH), blockHash);
			}
			pid = prevBlock.getBlockId();
		}
		if(pid != blockId) {
			return error(ErrorType.PREV_BLOCK_ID, pid, block.getBlockId(), blockHash);
		}
		int dupBl = blockChain.getBlockDepth(block.getHash());
		if(dupBl != -1) {
			return error(ErrorType.BLOCK_HASH_IS_DUPLICATED, blockHash, block.getBlockId(), dupBl);
		}
		return true;
	}
	
	public boolean validateSignatures(OpBlockChain ctx, OpOperation ob) throws FailedVerificationException {
		List<String> sigs = ob.getSignatureList();
		List<String> signedBy = ob.getSignedBy();
		if (signedBy.size() != sigs.size()) {
			return false;
		}
		byte[] txHash = SecUtils.getHashBytes(ob.getHash());
		boolean firstSignup = false;
		String signupName = "";
		if(OperationsRegistry.OP_SIGNUP.equals(ob.getType()) && ob.getNew().size() == 1) {
			OpObject obj = ctx.getObjectByName(OperationsRegistry.OP_SIGNUP, signupName);
			firstSignup = obj == null;
			signupName =  ob.getNew().get(0).getName();
		}
		// 1st signup could be signed by itself
		for (int i = 0; i < sigs.size(); i++) {
			String sig = sigs.get(i);
			String signedByName = signedBy.get(i);
			OpObject keyObj;
			if(firstSignup && signedByName.equals(signupName)) {
				keyObj = ob.getNew().get(0);
			} else {
				int n = signedByName.indexOf(USER_LOGIN_CHAR);
				if(n == -1) {
					keyObj = ctx.getObjectByName(OperationsRegistry.OP_SIGNUP, signedByName);
				} else  {
					keyObj = ctx.getObjectByName(OperationsRegistry.OP_LOGIN, signedByName.substring(0, n),
							signedByName.substring(n + 1));
				}
			}
			KeyPair kp = getKeyPairFromObj(keyObj, null);
			boolean validate = SecUtils.validateSignature(kp, txHash, sig);
			if (!validate) {
				return false;
			}
		}
		return true;
	}

	public boolean validateHash(OpOperation o) {
		return OUtils.equals(calculateOperationHash(o, false), o.getHash());
	}

	public KeyPair getSignUpKeyPairFromPwd(String name, String pwd) throws FailedVerificationException {
		OpOperation op = getSignUpOperation(name);
		if (op == null) {
			return null;
		}
		String algo = op.getStringValue(F_ALGO);
		KeyPair keyPair = SecUtils.generateKeyPairFromPassword(algo, op.getStringValue(F_KEYGEN_METHOD),
				op.getStringValue(F_SALT), pwd);
		KeyPair kp = SecUtils.getKeyPair(algo, null, op.getStringValue(F_PUBKEY));
		if (SecUtils.validateKeyPair(algo, keyPair.getPrivate(), kp.getPublic())) {
			return keyPair;
		}
		return null;
	}


	private KeyPair getKeyPairFromObj(OpObject op, String privatekey) throws FailedVerificationException {
		String algo = op.getStringValue(F_ALGO);
		KeyPair kp = SecUtils.getKeyPair(algo, privatekey, op.getStringValue(F_PUBKEY));
		if (privatekey == null || SecUtils.validateKeyPair(algo, kp.getPrivate(), kp.getPublic())) {
			return kp;
		}
		return null;
	}

	
	private void signBlock(OpBlock block, OpBlock prevOpBlock, String serverUser, KeyPair serverKeyPair)
			throws FailedVerificationException {
		block.setDate(OpBlock.F_DATE, System.currentTimeMillis());
		block.putObjectValue(OpBlock.F_BLOCKID, (Integer) (prevOpBlock.getBlockId() + 1));
		block.putStringValue(OpBlock.F_PREV_BLOCK_HASH, prevOpBlock.getHash());
		block.putStringValue(OpBlock.F_MERKLE_TREE_HASH, calculateMerkleTreeHash(block));
		block.putStringValue(OpBlock.F_SIG_MERKLE_TREE_HASH, calculateSigMerkleTreeHash(block));
		if (serverUser != null) {
			block.putStringValue(OpBlock.F_SIGNED_BY, serverUser);
		}
		block.putObjectValue(OpBlock.F_VERSION, BLOCK_VERSION);
		block.putObjectValue(OpBlock.F_EXTRA, BLOCK_EXTRA);
		block.putStringValue(OpBlock.F_DETAILS, BLOCK_CREATION_DETAILS);
		block.putStringValue(OpBlock.F_HASH, calculateHash(block));
		byte[] hashBytes = SecUtils.getHashBytes(block.getHash());
		if (serverKeyPair != null) {
			block.putStringValue(OpBlock.F_SIGNATURE,
					SecUtils.signMessageWithKeyBase64(serverKeyPair, hashBytes, SecUtils.SIG_ALGO_ECDSA, null));
		}
	}
	
	public String calculateHash(OpBlock block) {
		ByteArrayOutputStream bs = new ByteArrayOutputStream();
		DataOutputStream dous = new DataOutputStream(bs);
		try {
			dous.writeInt(block.getIntValue(OpBlock.F_VERSION, BLOCK_VERSION));
			dous.writeInt(block.getBlockId());
			dous.write(SecUtils.getHashBytes(block.getStringValue(OpBlock.F_PREV_BLOCK_HASH)));
			dous.writeLong(block.getDate(OpBlock.F_DATE));
			dous.write(SecUtils.getHashBytes(block.getStringValue(OpBlock.F_MERKLE_TREE_HASH)));
			dous.write(SecUtils.getHashBytes(block.getStringValue(OpBlock.F_SIG_MERKLE_TREE_HASH)));
			dous.writeLong(block.getLongValue(OpBlock.F_EXTRA, BLOCK_EXTRA));
			if(!OUtils.isEmpty(block.getStringValue(OpBlock.F_DETAILS))) {
				dous.write(block.getStringValue(OpBlock.F_DETAILS).getBytes("UTF-8"));
			}
			dous.write(block.getStringValue(OpBlock.F_DETAILS).getBytes("UTF-8"));
			dous.flush();
			return SecUtils.calculateHashWithAlgo(SecUtils.HASH_SHA256, bs.toByteArray());
		} catch (IOException e) {
			throw new IllegalArgumentException(e);
		}
	}

	
	public boolean error(ErrorType e, Object... args) {
		throw new IllegalArgumentException(e.getErrorFormat(args));
	}
	
	public static enum ErrorType {
		PREV_BLOCK_HASH("Previous block hash is not equal '%s' != '%s': block '%s'"),
		PREV_BLOCK_ID("Previous block id is not equal '%d' != '%d': block '%s'"),
		BLOCK_HASH_IS_DUPLICATED("Block hash is duplicated '%s' in block '%d' and '%d'"),
		OP_HASH_IS_DUPLICATED("Operation hash is duplicated '%s' in block '%d' and '%s'"),
		DEL_OBJ_NOT_FOUND("Object to delete wasn't found '%s': op '%s'"),
		REF_OBJ_NOT_FOUND("Object to reference wasn't found '%s': op '%s'"),
		DEL_OBJ_DOUBLE_DELETED("Object '%s' was already deleted at '%s' in block '%s'");
		private final String msg;

		ErrorType(String msg) {
			this.msg = msg;
		}
		
		public String getErrorFormat(Object... args) {
			return String.format(msg, args);
		}
	}
}
