package org.openplacereviews.opendb.service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.openplacereviews.opendb.FailedVerificationException;
import org.openplacereviews.opendb.SecUtils;
import org.openplacereviews.opendb.Utils;
import org.openplacereviews.opendb.api.ApiController;
import org.openplacereviews.opendb.ops.OpBlock;
import org.openplacereviews.opendb.ops.OpDefinitionBean;
import org.openplacereviews.opendb.ops.OperationsRegistry;
import org.openplacereviews.opendb.ops.auth.LoginOperation;
import org.openplacereviews.opendb.ops.auth.SignUpOperation;
import org.springframework.stereotype.Service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

@Service
public class OpenDBUsersRegistry {

	private Gson gson;
    
 	// signature section
 	public static final String F_FORMAT = "format";
 	public static final String F_ALGO = "algo";
 	public static final String F_TYPE = "type";
 	public static final String F_DIGEST = "digest";
 	
 	public static final String JSON_MSG_TYPE = "json";


	private ActiveUsersContext blockUsers;
	private ActiveUsersContext queueUsers;
 	
	public OpenDBUsersRegistry() {
		GsonBuilder builder = new GsonBuilder();
		builder.disableHtmlEscaping();
		builder.registerTypeAdapter(OpDefinitionBean.class, new OpDefinitionBean.OpDefinitionBeanAdapter());
		gson = builder.create();
		blockUsers = new ActiveUsersContext(null);
		queueUsers = new ActiveUsersContext(blockUsers);
	}
	
	
	// operations to parse / format related
	public InputStream getBlock(String id) {
    	return ApiController.class.getResourceAsStream("/bootstrap/ogr-"+id+".json");
    }
	
	public OpBlock parseBootstrapBlock(String id) {
		return gson.fromJson(new InputStreamReader(getBlock(id)), OpBlock.class);
	}
	
	public OpDefinitionBean parseOperation(String opJson) {
		return gson.fromJson(opJson, OpDefinitionBean.class);
	}
	public String toJson(OpBlock bl) {
		return gson.toJson(bl);
	}
	
	public String toJson(OpDefinitionBean op) {
		return gson.toJson(op);
	}

	// hash and signature operations
	public String calculateOperationHash(OpDefinitionBean ob, boolean set) {
		String oldHash = (String) ob.remove(OpDefinitionBean.F_HASH);
		Object sig = ob.remove(OpDefinitionBean.F_SIGNATURE);
		
		String hash = SecUtils.calculateHash(SecUtils.HASH_SHA256, null, gson.toJson(ob));
		if(set) {
			ob.putStringValue(OpDefinitionBean.F_HASH,  hash);
		} else {
			ob.putStringValue(OpDefinitionBean.F_HASH, oldHash);
		}
		ob.putObjectValue(OpDefinitionBean.F_SIGNATURE, sig);
		return hash;
	}
	
	public String toValidateSignatureJson(OpDefinitionBean op) {
		Object sig = op.remove(OpDefinitionBean.F_SIGNATURE);
		String json = gson.toJson(op);
		op.putObjectValue(OpDefinitionBean.F_SIGNATURE, sig);
		return json;
	}

	
	public OpDefinitionBean generateHashAndSign(OpDefinitionBean op, KeyPair... keyPair) throws FailedVerificationException {
		calculateOperationHash(op, true);
    	String json = toValidateSignatureJson(op);
    	op.remove(OpDefinitionBean.F_SIGNATURE);
    	if(keyPair.length == 1) {
    		op.putObjectValue(OpDefinitionBean.F_SIGNATURE, getSignature(json, keyPair[0]));
    	} else {
    		List<Map<String, String>> lst = new ArrayList<Map<String,String>>();
    		for(KeyPair k : keyPair) {
    			lst.add(getSignature(json, k));
    		}
    		op.putObjectValue(OpDefinitionBean.F_SIGNATURE, lst);
    	}
    	return op;
	}



	private Map<String, String> getSignature(String json, KeyPair keyPair) throws FailedVerificationException {
		String signature = SecUtils.signMessageWithKeyBase64(keyPair, json, SecUtils.SIG_ALGO_SHA1_EC);
    	Map<String, String> signatureMap = new TreeMap<>();
    	signatureMap.put(F_DIGEST, signature);
    	signatureMap.put(F_TYPE, "json");
    	signatureMap.put(F_ALGO, SecUtils.SIG_ALGO_SHA1_EC);
    	signatureMap.put(F_FORMAT, SecUtils.DECODE_BASE64);
		return signatureMap;
	}
	

	public boolean validateSignatures(ActiveUsersContext ctx, OpDefinitionBean ob) throws FailedVerificationException {
		if (ob.hasOneSignature()) {
			Map<String, String> sig = ob.getStringMap(OpDefinitionBean.F_SIGNATURE);
				boolean validate = validateSignature(ctx, ob, sig, ob.getSignedBy());
				if(!validate) {
					return false;
				}
		} else {
			List<Map<String, String>> sigs = ob.getListStringMap(OpDefinitionBean.F_SIGNATURE);
			for (int i = 0; i < sigs.size(); i++) {
				Map<String, String> sig = sigs.get(i);
					boolean validate = validateSignature(ctx, ob, sig, i == 0 ? ob.getSignedBy() : ob.getOtherSignedBy()
							.get(i - 1));
					if(!validate) {
						return false;
					}
			}
		}
		return true;
	}
	
	public boolean validateHash(OpDefinitionBean o) {
		return Utils.equals(calculateOperationHash(o, false), o.getHash());
	}
	
	public boolean validateSignature(ActiveUsersContext ctx, OpDefinitionBean ob, Map<String, String> sig, String name) throws FailedVerificationException {
		if (sig == null) {
			return false;
		}
		if (name == null) {
			return false;
		}
		String sigAlgo = sig.get(F_ALGO);
		byte[] signature = SecUtils.decodeSignature(sig.get(F_FORMAT), sig.get(F_DIGEST));
		String msgType = sig.get(F_TYPE);
		String msg;
		if (!JSON_MSG_TYPE.equals(msgType)) {
			return false;
		} else {
			msg = toValidateSignatureJson(ob);
		}
		if (ob.getOperationId().equals(SignUpOperation.OP_ID) && ob.getStringValue(SignUpOperation.F_NAME).equals(name)) {
			// signup operation is validated by itself
			KeyPair kp = getPublicKeyFromOp(ob);
			return SecUtils.validateSignature(kp, msg, sigAlgo, signature);
		} else if (ob.getOperationId().equals(LoginOperation.OP_ID)
				&& ob.getStringValue(SignUpOperation.F_NAME).equals(name)) {
			// login operation is validated only by sign up
			OpDefinitionBean signUp = ctx.getSignUpOperation(name);
			KeyPair kp = getPublicKeyFromOp(signUp);
			return SecUtils.validateSignature(kp, msg, sigAlgo, signature);
		} else {
			List<OpDefinitionBean> logins = ctx.getLoginOperations(name, new ArrayList<OpDefinitionBean>());
			// other operations are validated by any login
			for (OpDefinitionBean login : logins) {
				KeyPair kp = getPublicKeyFromOp(login);
				boolean vl = SecUtils.validateSignature(kp, msg, sigAlgo, signature);
				if (vl) {
					return vl;
				}
			}
		}
		return false;
	}

	private KeyPair getPublicKeyFromOp(OpDefinitionBean ob) throws FailedVerificationException {
		if(ob == null) {
			return null;
		}
		String signUpalgo = ob.getStringValue(F_ALGO);
		String pbKey = ob.getStringValue(SignUpOperation.F_PUBKEY);
		return SecUtils.getKeyPair(signUpalgo, null, pbKey);
	}

	

	// Users related operations
	public ActiveUsersContext getBlockUsers() {
		return blockUsers;
	}
	
	public ActiveUsersContext getQueueUsers() {
		return queueUsers;
	}


 	protected static class ActiveUser {
 		protected String name;
 		protected OpDefinitionBean signUp;
 		protected List<OpDefinitionBean> logins = new ArrayList<OpDefinitionBean>();
 		
 	}
 	
 	
 	public static class ActiveUsersContext {
 		
		ActiveUsersContext parent;
		Map<String, ActiveUser> users = new ConcurrentHashMap<String, ActiveUser>();

		public ActiveUsersContext(ActiveUsersContext parent) {
			this.parent = parent;
		}

		public List<OpDefinitionBean> getLoginOperations(String name, List<OpDefinitionBean> list) {
			if (parent != null) {
				parent.getLoginOperations(name, list);
			}
			ActiveUser au = users.get(name);
			if (au == null || au.signUp == null) {
				list.addAll(au.logins);
			}
			return list;
		}

 		public boolean removeAuthOperation(String name, OpDefinitionBean op, boolean deep) {
 			String h = op.getHash();
 			if(h == null) {
 				return false;
 			}
 			boolean deleted = false;
 			if(deep && parent != null) {
 				deleted = parent.removeAuthOperation(name, op, deep);
 			}
 			ActiveUser au = users.get(name);
			if (au != null && au.signUp != null) {
				if(Utils.equals(au.signUp.getHash(), h)) {
					au.signUp = null;
					deleted = true;
				}
				Iterator<OpDefinitionBean> it = au.logins.iterator();
				while(it.hasNext()) {
					OpDefinitionBean o = it.next();
					if(Utils.equals(o.getHash(), h)) {
						it.remove();
						deleted = true;
					}
				}
			}
			return deleted;
 		}
 		
		public boolean addAuthOperation(OpDefinitionBean op) {
			if(!op.getType().equals(OperationsRegistry.OP_TYPE_AUTH)) {
				return false;
			}
			String name = op.getStringValue(SignUpOperation.F_NAME);
			ActiveUser au = users.get(name);
			if (au == null) {
				au = new ActiveUser();
				au.name = name;
				users.put(name, au);
			}
			if (op.getOperationId().equals(SignUpOperation.OP_ID)) {
				OpDefinitionBean sop = getSignUpOperation(name);
				if (sop != null) {
					throw new IllegalArgumentException("User was already signed up");
				}
				au.signUp = op;
				return true;
			} else if (op.getOperationId().equals(LoginOperation.OP_ID)) {
				au.logins.add(op);
				return true;
			}
			return false;
		}
 		
 		public OpDefinitionBean getSignUpOperation(String name) {
 			OpDefinitionBean op = null;
 			if(parent != null) {
 				 op = parent.getSignUpOperation(name);
 			}
 			if(op == null) {
 				ActiveUser au = users.get(name);
 	 			if(au == null || au.signUp == null) {
 	 				return null;
 	 			}	
 	 			op = au.signUp;
 			}
 			return op;
 		}
 		
 		public KeyPair getSignUpKeyPairFromPwd(String name, String pwd) throws FailedVerificationException {
 			OpDefinitionBean op = getSignUpOperation(name);
 			if(op == null) {
 				return null;
 			}
 			String algo = op.getStringValue(SignUpOperation.F_ALGO);
 			KeyPair keyPair = SecUtils.generateKeyPairFromPassword(
 					algo, op.getStringValue(SignUpOperation.F_KEYGEN_METHOD), 
 					op.getStringValue(SignUpOperation.F_SALT), pwd);
 			KeyPair kp = SecUtils.getKeyPair(algo, null, op.getStringValue(SignUpOperation.F_PUBKEY));
 			if(SecUtils.validateKeyPair(algo, keyPair.getPrivate(), kp.getPublic())) {
 				return keyPair;
 			}
 			return null;
 		}
 		
 	
 		
 		public KeyPair getSignUpKeyPair(String name, String privatekey) throws FailedVerificationException {
 			OpDefinitionBean op = getSignUpOperation(name);
 			if(op == null) {
 				return null;
 			}
 			String algo = op.getStringValue(SignUpOperation.F_ALGO);
 			KeyPair kp = SecUtils.getKeyPair(algo, privatekey, op.getStringValue(SignUpOperation.F_PUBKEY));
 			if(SecUtils.validateKeyPair(algo, kp.getPrivate(), kp.getPublic())) {
 				return kp;
 			}
 			return null;
 		}
 		
 		public KeyPair getLoginKeyPair(String name, String privateKey) throws FailedVerificationException {
 			ActiveUser au = users.get(name);
 			if(au == null || au.signUp == null) {
 				throw new IllegalStateException(String.format("User '%s' is not signed up", name));
 			}
 			for (OpDefinitionBean op : au.logins) {
 				String algo = op.getStringValue(SignUpOperation.F_ALGO);
 				KeyPair kp = SecUtils.getKeyPair(algo, privateKey,op.getStringValue(SignUpOperation.F_PUBKEY));
 				if (SecUtils.validateKeyPair(algo, kp.getPrivate(), kp.getPublic())) {
 					return kp;
 				}
 			}
 			return null;
 		}


 	}


	
 	
}
