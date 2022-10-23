package com.nextlabs.ldif;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.bluejungle.framework.expressions.EvalValue;
import com.bluejungle.framework.expressions.Multivalue;
import com.nextlabs.cache.CacheEngine;
import com.nextlabs.common.PDPObject;
import com.nextlabs.common.Provider;
import com.nextlabs.common.Util;
import com.nextlabs.exception.InvalidProfileException;
import com.novell.ldap.LDAPAttribute;
import com.novell.ldap.LDAPAttributeSet;
import com.novell.ldap.LDAPEntry;
import com.novell.ldap.LDAPLocalException;
import com.novell.ldap.LDAPMessage;
import com.novell.ldap.LDAPSearchResult;
import com.novell.ldap.util.LDIFReader;

public class LDIFProvider implements Provider {
	private boolean isProviderset;
	private static final Log LOG = LogFactory.getLog(LDIFProvider.class);
	private static LDIFProvider provider;
	private Properties poolProps;
	private int page_size;
	private Map<String, LDIFProfile> profiles;
	private LDIFProfile singleProfile;
	private Map<String, List<String>> userAttributeToProfileMap;
	private Map<String, List<String>> groupAttributeToProfileMap;
	private boolean isSingleProfile;
	private Map<String, String> idToObjectTypeMap;
	private final String USER_TYPE = "user";
	private final String GROUP_TYPE = "group";
	private int numberOfRetries;
	private int intervalBetweenRetries;
	private Boolean isRefreshing;
	private static Properties commonProp;
	private InputStream is;
	private LDIFReader ldifReader;

	private final String FILTER_DISABLED_ACCOUNTS = "(!(userAccountControl:1.2.840.113556.1.4.803:=2))";

	public LDIFProvider() {
		poolProps = new Properties();
		userAttributeToProfileMap = new HashMap<String, List<String>>();
		groupAttributeToProfileMap = new HashMap<String, List<String>>();
		idToObjectTypeMap = new ConcurrentHashMap<String, String>();
		isRefreshing = false;
	}

	public static LDIFProvider getInstance() {
		if (provider == null) {
			provider = new LDIFProvider();
		}

		return provider;
	}

	public LdapContext getContextFromPool(LDIFProfile profile)
			throws NamingException {

		Hashtable<String, String> env = new Hashtable<String, String>();
		env.put(Context.INITIAL_CONTEXT_FACTORY,
				"com.sun.jndi.ldap.LdapCtxFactory");
		env.put(LdapContext.CONTROL_FACTORIES,
				"com.sun.jndi.ldap.ControlFactory");
		env.put(Context.STATE_FACTORIES, "PersonStateFactory");
		env.put(Context.OBJECT_FACTORIES, "PersonObjectFactory");

		return new InitialLdapContext(env, null);

	}

	@Override
	public void setCommonProperties(Properties props) {
		commonProp = props;

		poolProps.put("com.sun.jndi.ldap.connect.pool.maxsize",
				props.getProperty("pool_max_size", "20"));
		poolProps.put("com.sun.jndi.ldap.connect.pool.prefsize",
				props.getProperty("pool_pref_size", "10"));
		poolProps.put("com.sun.jndi.ldap.connect.pool.initsize",
				props.getProperty("pool_init_size", "1"));
		poolProps.put("com.sun.jndi.ldap.connect.pool.timeout",
				props.getProperty("pool_time_out", "30000"));
		poolProps.put("com.sun.jndi.ldap.connect.pool.protocol",
				props.getProperty("pool_protocol", "plain ssl"));

		if (!props.getProperty("pool_debug", "none").equals("none")) {
			poolProps.put("com.sun.jndi.ldap.connect.pool.debug",
					props.getProperty("pool_debug"));
		}

		poolProps.put("com.sun.jndi.ldap.connect.pool.authentication",
				"none simple");
		try {
			page_size = Integer.parseInt(props.getProperty("paging_size",
					"1000"));
		} catch (NumberFormatException nfe) {
			page_size = 1000;
		}

	}

	@Override
	public PDPObject getPDPObject(String id, String attributeToSearch)
			throws NamingException {

		PDPObject object = null;

		if (idToObjectTypeMap.get(id) == null
				|| (idToObjectTypeMap.get(id) != null && idToObjectTypeMap.get(
						id).equals(USER_TYPE))) {

			if (isSingleProfile) {

				object = queryForUser(singleProfile, id);

			} else {

				List<String> profilesToLook = userAttributeToProfileMap
						.get(attributeToSearch.toLowerCase());
				if (profilesToLook == null) {
					LOG.error(String.format(
							"Attribute [%s] isn't provided by any domain",
							attributeToSearch));
					return null;
				}

				for (String profileName : profilesToLook) {
					LDIFProfile ldapProfile = profiles.get(profileName);

					LOG.info(String
							.format("Attribute [%s] should be found in domain [%s]. Attemp to query...",
									attributeToSearch, ldapProfile.getName()));

					object = queryForUser(ldapProfile, id);

					if (object != null) {
						break;
					}
				}
			}
		} else if (idToObjectTypeMap.get(id) != null
				&& idToObjectTypeMap.get(id).equals(GROUP_TYPE)) {
			if (isSingleProfile) {

				object = queryForGroup(singleProfile, id);

			} else {

				List<String> profilesToLook = groupAttributeToProfileMap
						.get(attributeToSearch.toLowerCase());
				if (profilesToLook == null) {
					LOG.error(String.format(
							"Attribute [%s] isn't provided by any domain",
							attributeToSearch));
					return null;
				}

				for (String profileName : profilesToLook) {
					LDIFProfile ldapProfile = profiles.get(profileName);

					LOG.info(String
							.format("Attribute [%s] should be found in domain [%s]. Attemp to query...",
									attributeToSearch, ldapProfile.getName()));

					object = queryForGroup(ldapProfile, id);

					if (object != null) {
						break;
					}
				}
			}
		} else {
			LOG.error(String.format("Type cannot be found for ID [%s]", id));
		}

		if (object == null) {
			LOG.error(String
					.format("Object [%s] cannot be queried from AD", id));
		}

		return object;
	}

	private PDPObject queryForUser(LDIFProfile ldapProfile, String userId)
			throws NamingException {
		PDPObject user = null;

		return user;
	}

	private PDPObject queryForGroup(LDIFProfile ldapProfile, String groupId)
			throws NamingException {
		PDPObject group = null;

		return group;
	}

	@Override
	public synchronized void refreshCache() {

		if (!commonProp.getProperty("cache_refresh_period", "0")
				.equalsIgnoreCase("0")) {

			isRefreshing = true;

			long startTime = System.currentTimeMillis();

			LOG.info(String.format("Page size is %d", page_size));

			int count = 0;

			while (true) {
				try {
					if (isSingleProfile) {
						refreshProfile(singleProfile);
					} else {
						for (LDIFProfile ldapProfile : profiles.values()) {
							refreshProfile(ldapProfile);
						}
					}
					break;

				} catch (Exception e) {

					LOG.error("Cache refresh encountered an exception.", e);

					if (count++ == numberOfRetries) {
						LOG.error(String.format(
								"Attempted [%d] retries without success.",
								numberOfRetries));
						break;
					} else {
						LOG.debug(String.format(
								"Retrying refreshing cache in [%d] seconds..",
								intervalBetweenRetries));
						try {
							Thread.sleep(intervalBetweenRetries * 1000);
						} catch (InterruptedException ie) {
							// IGNORE
						}
					}
				}
			}

			long endTime = System.currentTimeMillis();

			isRefreshing = false;

			LOG.info("Cache refresh completed for LDIF Provider");
			LOG.info("Time Taken: " + Long.toString((endTime - startTime))
					+ "ms");

		} else {
			LOG.info("Skip reload cache since the cache_refresh_period is 0");
		}
	}

	private void refreshProfile(LDIFProfile ldifProfile) throws Exception {
		LOG.info(String.format("Started refreshing domain [%s]",
				ldifProfile.getName()));

		if (!ldifProfile.getIsValid()) {
			LOG.error(String.format(
					"Profile [%s] is invalid. Skip refreshing.",
					ldifProfile.getName()));
			return;
		}

		refreshUser(ldifProfile);

		if (ldifProfile.getWithGroup()) {
			refreshGroup(ldifProfile);
		}

	}

	// read a inputstream in a serial order
	private class CombinedInputStream extends InputStream {
		private final InputStream[] streams;
		private int index = 0;

		private CombinedInputStream(InputStream... streams) {
			this.streams = streams;
		}

		@Override
		public int read() throws IOException {
			if (index >= streams.length) {
				return -1;
			}

			int r;
			r = streams[index].read();
			// try to read next one
			if (r == -1) {
				index++;
				r = read();
			}
			return r;
		}

		/**
		 * @throws IOException
		 *             , only the last IOException
		 */
		@Override
		public void close() throws IOException {
			IOException ioe = null;
			for (InputStream stream : streams) {
				try {
					stream.close();
				} catch (IOException e) {
					ioe = e;
				}
			}

			if (ioe != null) {
				throw ioe;
			}
		}
	}

	private void refreshUser(LDIFProfile ldifProfile) throws Exception {

		if (ldifProfile.getLdifFilePath() != null
				&& ldifProfile.getLdifFilePath().length() > 0) {
			int count = 0;
			String objectuserkey = "objectclass";
			String objectuserfilter = "user";
			String filter = ldifProfile.getUserSearchFilter();
			StringTokenizer tokens = new StringTokenizer(filter, "(=)");
			if (tokens.hasMoreTokens()) {
				objectuserkey = tokens.nextToken();
			}
			if (tokens.hasMoreTokens()) {
				objectuserfilter = tokens.nextToken();
			}
			FileInputStream fis = null;
			try {
				fis = new FileInputStream(ldifProfile.getLdifFilePath());
				BufferedReader bufReader = new BufferedReader(
						new InputStreamReader(fis));
				String line;
				while ((line = bufReader.readLine()) != null
						&& (line.length() == 0 || line.startsWith("#"))) {
				}

				if ((line != null) && line.startsWith("version:")) {
					is = new FileInputStream(ldifProfile.getLdifFilePath());
				} else {
					is = new CombinedInputStream(new ByteArrayInputStream(
							"version:1\n".getBytes()), new FileInputStream(
							ldifProfile.getLdifFilePath()));
				}
			} catch (FileNotFoundException e) {
				throw new Exception("Ldif file does not exist:"
						+ ldifProfile.getLdifFilePath());
			} catch (IOException e) {
				throw new Exception("Can't read: "
						+ ldifProfile.getLdifFilePath(), e);
			} finally {
				if (fis != null) {
					try {
						fis.close();
					} catch (Exception e) {
						LOG.warn(
								"Failed to close file "
										+ ldifProfile.getLdifFilePath(), e);
					}
				}
			}

			try {
				ldifReader = new LDIFReader(is);
				LDAPMessage msg = null;
				String lastParsedDn = null;

				while (((msg = ldifReader.readMessage()) != null)) {
					LDAPEntry entry = ((LDAPSearchResult) msg).getEntry();

					lastParsedDn = entry.getDN();
					String objectClassValue = null;
					if (entry.getAttribute(objectuserkey) != null) {
						objectClassValue = entry.getAttribute(objectuserkey)
								.getStringValue();
					}
					if ((objectClassValue == null)
							|| (objectClassValue != null && !objectClassValue
									.equalsIgnoreCase(objectuserfilter))) {
						continue;
					}
					PDPObject user = produceUser(entry, ldifProfile);
					LOG.trace(" Got LDIF entry:" + lastParsedDn);
					CacheEngine.getInstance().writeObjectToCache(user);

					// update identifier map
					for (String key : ldifProfile.getUserKeyAttributes()) {

						if (user.getAttribute(key.toLowerCase()) != null
								&& user.getAttribute(key.toLowerCase())
										.getValue() != null) {

							Object keyAttributeValue = user.getAttribute(
									key.toLowerCase()).getValue();

							LOG.debug(String.format(
									"Put into identifier map [%s] - [%s]",
									(String) keyAttributeValue, user.getId()));

							count++;

							CacheEngine.getInstance().addIdentifier(
									(String) keyAttributeValue, user.getId());

							idToObjectTypeMap.put((String) keyAttributeValue,
									USER_TYPE);
						}
					}
				}

			} catch (LDAPLocalException e) {
				throw new Exception(e);
			} catch (IOException e) {
				throw new Exception(e);
			} finally {
				fis.close();
			}

			System.out
					.println(String.format("User cache count is [%d]", count));
			LOG.info(String.format("User cache count is [%d]", count));
		} else {
			throw new Exception("LDIF File not able to open");
		}
	}

	private void refreshGroup(LDIFProfile ldifProfile) throws Exception {

		if (ldifProfile.getLdifFilePath() != null
				&& ldifProfile.getLdifFilePath().length() > 0) {
			int count = 0;
			String objectgroupkey = "objectclass";
			String objectgroupvalue = "user";
			String filter = ldifProfile.getGroupSearchFilter();
			StringTokenizer tokens = new StringTokenizer(filter, "(=)");
			if (tokens.hasMoreTokens()) {
				objectgroupkey = tokens.nextToken();
			}
			if (tokens.hasMoreTokens()) {
				objectgroupvalue = tokens.nextToken();
			}
			FileInputStream fis = null;
			try {
				fis = new FileInputStream(ldifProfile.getLdifFilePath());
				BufferedReader bufReader = new BufferedReader(
						new InputStreamReader(fis));
				String line;
				while ((line = bufReader.readLine()) != null
						&& (line.length() == 0 || line.startsWith("#"))) {
				}

				if ((line != null) && line.startsWith("version:")) {
					is = new FileInputStream(ldifProfile.getLdifFilePath());
				} else {
					is = new CombinedInputStream(new ByteArrayInputStream(
							"version:1\n".getBytes()), new FileInputStream(
							ldifProfile.getLdifFilePath()));
				}
			} catch (FileNotFoundException e) {
				throw new Exception("Ldif file does not exist:"
						+ ldifProfile.getLdifFilePath());
			} catch (IOException e) {
				throw new Exception("Can't read: "
						+ ldifProfile.getLdifFilePath(), e);
			} finally {
				if (fis != null) {
					try {
						fis.close();
					} catch (Exception e) {
						LOG.warn(
								"Failed to close file "
										+ ldifProfile.getLdifFilePath(), e);
					}
				}
			}

			try {
				ldifReader = new LDIFReader(is);
				LDAPMessage msg = null;
				String lastParsedDn = null;

				while (((msg = ldifReader.readMessage()) != null)) {
					LDAPEntry entry = ((LDAPSearchResult) msg).getEntry();

					lastParsedDn = entry.getDN();
					String objectClassValue = null;
					if (entry.getAttribute(objectgroupkey) != null) {
						objectClassValue = entry.getAttribute(objectgroupkey)
								.getStringValue();
					}
					if ((objectClassValue == null)
							|| (objectClassValue != null && !objectClassValue
									.equalsIgnoreCase(objectgroupvalue))) {
						continue;
					}
					PDPObject group = produceGroup(entry, ldifProfile);
					LOG.trace(" Got LDIF entry:" + lastParsedDn);
					CacheEngine.getInstance().writeObjectToCache(group);

					// update identifier map
					for (String key : ldifProfile.getUserKeyAttributes()) {

						if (group.getAttribute(key.toLowerCase()) != null
								&& group.getAttribute(key.toLowerCase())
										.getValue() != null) {

							Object keyAttributeValue = group.getAttribute(
									key.toLowerCase()).getValue();

							LOG.debug(String.format(
									"Put into identifier map [%s] - [%s]",
									(String) keyAttributeValue, group.getId()));

							count++;

							CacheEngine.getInstance().addIdentifier(
									(String) keyAttributeValue, group.getId());

							idToObjectTypeMap.put((String) keyAttributeValue,
									GROUP_TYPE);
						}
					}
				}

			} catch (LDAPLocalException e) {
				throw new Exception(e);
			} catch (IOException e) {
				throw new Exception(e);
			} finally {
				fis.close();
			}

			System.out.println(String
					.format("Group cache count is [%d]", count));
			LOG.info(String.format("Group cache count is [%d]", count));
		} else {
			throw new Exception("LDIF File not able to open");
		}
	}

	private PDPObject produceUser(LDAPEntry entry, LDIFProfile profile)
			throws NamingException {

		PDPObject user = null;

		LDAPAttributeSet attrs = entry.getAttributeSet();

		String[] ids = new String[profile.getUserKeyAttributes().size()];

		for (int i = 0; i < profile.getUserKeyAttributes().size(); i++) {

			String keyAttributeName = profile.getUserKeyAttributes().get(i);

			LDAPAttribute temp = attrs.getAttribute(profile
					.getUserKeyAttributes().get(i));
			if (temp == null || temp.getStringValue() == null) {
				ids[i] = "UNDEFINED";
			} else {
				// object sid data is in binary, need to convert to string
				if (keyAttributeName.trim().equals("objectSid")) {
					ids[i] = convertWindowsSID(temp.getByteValue());
				} else {
					ids[i] = temp.getStringValue();
				}

				if (!profile.isUserKeyCaseSensitive(keyAttributeName)) {
					ids[i] = ids[i].toLowerCase();
				}
			}
		}

		String userId = Util.makeCombinedID(profile.getName(), ids);

		user = new PDPObject(profile.getName(), userId, USER_TYPE);

		List<String> sValues = new ArrayList<String>();

		// process attributes to pull
		for (int i = 0; i < profile.getUserAttributesToPull().size(); i++) {

			String attributeName = profile.getUserAttributesToPull().get(i);

			LDAPAttribute temp = attrs.getAttribute(attributeName);

			if (!profile.isUserMultiAttribute(attributeName)) {
				user.addAttribute(
						attributeName.toLowerCase(),
						(temp == null || temp.getStringValue() == null) ? EvalValue.NULL
								: EvalValue.build(temp.getStringValue()
										.toString()));
			} else {

				if (temp == null) {
					user.addAttribute(attributeName.toLowerCase(),
							EvalValue.build(Multivalue.EMPTY));
				} else {

					Enumeration values = temp.getStringValues();
					sValues.clear();

					while (values.hasMoreElements()) {
						sValues.add((String) values.nextElement());
					}

					if (sValues.size() > 0) {
						user.addAttribute(attributeName.toLowerCase(),
								EvalValue.build(Multivalue.create(sValues)));
					} else {
						user.addAttribute(attributeName.toLowerCase(),
								EvalValue.build(Multivalue.EMPTY));
					}
				}
			}

		}

		// process key attributes
		for (int i = 0; i < profile.getUserKeyAttributes().size(); i++) {
			String attributeName = profile.getUserKeyAttributes().get(i);

			LDAPAttribute temp = attrs.getAttribute(attributeName);

			if (temp != null && temp.getStringValue() != null) {

				Object value = temp.getStringValue();

				// object sid data is in binary, need to convert to string
				if (attributeName.equals("objectSid")) {
					value = convertWindowsSID((byte[]) value);
				}

				user.addAttribute(attributeName.toLowerCase(),
						EvalValue.build((profile
								.isUserKeyCaseSensitive(attributeName)) ? value
								.toString() : value.toString().toLowerCase()));
			}
		}

		return user;
	}

	private PDPObject produceGroup(LDAPEntry entry, LDIFProfile profile)
			throws NamingException {

		PDPObject group = null;

		LDAPAttributeSet attrs = entry.getAttributeSet();

		String[] ids = new String[profile.getGroupKeyAttributes().size()];

		for (int i = 0; i < profile.getGroupKeyAttributes().size(); i++) {

			String keyAttributeName = profile.getGroupKeyAttributes().get(i);

			LDAPAttribute temp = attrs.getAttribute(profile
					.getGroupKeyAttributes().get(i));
			if (temp == null || temp.getStringValue() == null) {
				ids[i] = "UNDEFINED";
			} else {
				// object sid data is in binary, need to convert to string
				if (keyAttributeName.trim().equals("objectSid")) {
					ids[i] = convertWindowsSID(temp.getByteValue());
				} else {
					ids[i] = temp.getStringValue();
				}

				if (!profile.isGroupKeyCaseSensitive(keyAttributeName)) {
					ids[i] = ids[i].toLowerCase();
				}
			}
		}

		String groupId = Util.makeCombinedID(profile.getName(), ids);

		group = new PDPObject(profile.getName(), groupId, USER_TYPE);

		List<String> sValues = new ArrayList<String>();

		// process attributes to pull
		for (int i = 0; i < profile.getGroupAttributesToPull().size(); i++) {

			String attributeName = profile.getGroupAttributesToPull().get(i);

			LDAPAttribute temp = attrs.getAttribute(attributeName);

			if (!profile.isGroupMultiAttribute(attributeName)) {
				group.addAttribute(
						attributeName.toLowerCase(),
						(temp == null || temp.getStringValue() == null) ? EvalValue.NULL
								: EvalValue.build(temp.getStringValue()
										.toString()));
			} else {

				if (temp == null) {
					group.addAttribute(attributeName.toLowerCase(),
							EvalValue.build(Multivalue.EMPTY));
				} else {

					Enumeration values = temp.getStringValues();
					sValues.clear();

					while (values.hasMoreElements()) {
						sValues.add((String) values.nextElement());
					}

					if (sValues.size() > 0) {
						group.addAttribute(attributeName.toLowerCase(),
								EvalValue.build(Multivalue.create(sValues)));
					} else {
						group.addAttribute(attributeName.toLowerCase(),
								EvalValue.build(Multivalue.EMPTY));
					}
				}
			}

		}

		// process key attributes
		for (int i = 0; i < profile.getGroupKeyAttributes().size(); i++) {
			String attributeName = profile.getGroupKeyAttributes().get(i);

			LDAPAttribute temp = attrs.getAttribute(attributeName);

			if (temp != null && temp.getStringValue() != null) {

				Object value = temp.getStringValue();

				// object sid data is in binary, need to convert to string
				if (attributeName.equals("objectSid")) {
					value = convertWindowsSID((byte[]) value);
				}

				group.addAttribute(
						attributeName.toLowerCase(),
						EvalValue.build((profile
								.isGroupKeyCaseSensitive(attributeName)) ? value
								.toString() : value.toString().toLowerCase()));
			}
		}

		return group;
	}

	@Override
	public void loadProfiles(Properties props) {

		profiles = new HashMap<String, LDIFProfile>();

		String sProfileNames = props.getProperty("profile_names");

		if (sProfileNames != null) {
			String[] profileNames = sProfileNames.split(",");

			for (String name : profileNames) {

				name = name.trim();
				if(props.getProperty(name + "_type")!=null && props.getProperty(name + "_type").equalsIgnoreCase("LDIF") )
				{
					isProviderset=true;
				}
				else
				{
					continue;
				}
				LOG.info(String.format("Loading profile of domain [%s]", name));
				LDIFProfile profile = new LDIFProfile(name);
				try {
					profile.parseProfile(props);
					profiles.put(profile.getName(), profile);

					for (String attr : profile.getUserAttributesToPull()) {
						if (userAttributeToProfileMap.containsKey(attr
								.toLowerCase())) {
							userAttributeToProfileMap.get(attr.toLowerCase())
									.add(profile.getName());
						} else {
							List<String> newIndex = new ArrayList<String>();
							newIndex.add(profile.getName());
							userAttributeToProfileMap.put(attr.toLowerCase(),
									newIndex);
						}
					}

					for (String attr : profile.getGroupAttributesToPull()) {
						if (groupAttributeToProfileMap.containsKey(attr
								.toLowerCase())) {
							groupAttributeToProfileMap.get(attr.toLowerCase())
									.add(profile.getName());
						} else {
							List<String> newIndex = new ArrayList<String>();
							newIndex.add(profile.getName());
							groupAttributeToProfileMap.put(attr.toLowerCase(),
									newIndex);
						}
					}

				} catch (InvalidProfileException ipe) {
					LOG.error(String.format("Invalid profile for domain [%s]",
							name), ipe);
				}

			}
		} else {
			LOG.warn("Profile names are undefined");
		}
	}

	@Override
	public void loadSingleProfile(Properties props) {
		String name = "LDIF";

		LOG.info(String.format("Loading profile of domain [%s]", name));
		LDIFProfile profile = new LDIFProfile(name);
		try {
			profile.parseProfile(props);
			singleProfile = profile;
		} catch (InvalidProfileException ipe) {
			LOG.error(String.format("Invalid profile for domain [%s]", name),
					ipe);
		}

	}

	public boolean getIsSingleProfile() {
		return isSingleProfile;
	}

	@Override
	public void setIsSingleProfile(Boolean isSingleProfile) {
		this.isSingleProfile = isSingleProfile;
	}

	private String convertWindowsSID(byte[] sid) {
		int offset, size;

		// sid[0] is the Revision, we allow only version 1, because it's the
		// only that exists right now.
		if (sid[0] != 1)
			throw new IllegalArgumentException("SID revision must be 1");

		StringBuilder stringSidBuilder = new StringBuilder("S-1-");

		// The next byte specifies the numbers of sub authorities (number of
		// dashes minus two)
		int subAuthorityCount = sid[1] & 0xFF;

		// IdentifierAuthority (6 bytes starting from the second) (big endian)
		long identifierAuthority = 0;
		offset = 2;
		size = 6;
		for (int i = 0; i < size; i++) {
			identifierAuthority |= (long) (sid[offset + i] & 0xFF) << (8 * (size - 1 - i));
			// The & 0xFF is necessary because byte is signed in Java
		}
		if (identifierAuthority < Math.pow(2, 32)) {
			stringSidBuilder.append(Long.toString(identifierAuthority));
		} else {
			stringSidBuilder.append("0x").append(
					Long.toHexString(identifierAuthority).toUpperCase());
		}

		// Iterate all the SubAuthority (little-endian)
		offset = 8;
		size = 4; // 32-bits (4 bytes) for each SubAuthority
		for (int i = 0; i < subAuthorityCount; i++, offset += size) {
			long subAuthority = 0;
			for (int j = 0; j < size; j++) {
				subAuthority |= (long) (sid[offset + j] & 0xFF) << (8 * j);
				// The & 0xFF is necessary because byte is signed in Java
			}
			stringSidBuilder.append("-").append(subAuthority);
		}

		return stringSidBuilder.toString();
	}

	@Override
	public String getPDPObjectType(String id) {
		return idToObjectTypeMap.get(id);
	}

	@Override
	public Boolean isRefreshing() {
		return isRefreshing;
	}
	@Override
	public boolean isProviderset() {
		return isProviderset;
	}
	@Override
	public void setProviderset(boolean isProviderset) {
		this.isProviderset = isProviderset;
	}



}
