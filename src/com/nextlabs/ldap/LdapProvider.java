package com.nextlabs.ldap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.Control;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.ldap.PagedResultsControl;
import javax.naming.ldap.PagedResultsResponseControl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.bluejungle.framework.expressions.EvalValue;
import com.bluejungle.framework.expressions.Multivalue;
import com.nextlabs.cache.CacheEngine;
import com.nextlabs.common.PDPObject;
import com.nextlabs.common.Provider;
import com.nextlabs.common.Util;
import com.nextlabs.exception.InvalidProfileException;

public class LdapProvider implements Provider {
	private boolean isProviderset;
	private static final Log LOG = LogFactory.getLog(LdapProvider.class);
	private static LdapProvider provider;
	private Properties poolProps;
	private int page_size;
	private Map<String, LdapProfile> profiles;
	private LdapProfile singleProfile;
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

	private final String FILTER_DISABLED_ACCOUNTS = "(!(userAccountControl:1.2.840.113556.1.4.803:=2))";

	public LdapProvider() {
		poolProps = new Properties();
		userAttributeToProfileMap = new HashMap<String, List<String>>();
		groupAttributeToProfileMap = new HashMap<String, List<String>>();
		idToObjectTypeMap = new ConcurrentHashMap<String, String>();
		isRefreshing = false;
	}

	public static LdapProvider getInstance() {
		if (provider == null) {
			provider = new LdapProvider();
		}

		return provider;
	}

	public LdapContext getContextFromPool(LdapProfile profile) throws NamingException {

		Hashtable<String, String> env = new Hashtable<String, String>();
		env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
		env.put(LdapContext.CONTROL_FACTORIES, "com.sun.jndi.ldap.ControlFactory");
		env.put(Context.STATE_FACTORIES, "PersonStateFactory");
		env.put(Context.OBJECT_FACTORIES, "PersonObjectFactory");
		env.put(Context.SECURITY_AUTHENTICATION, profile.getAuthentication());
		if(profile.getAuthentication().equalsIgnoreCase("simple")){
			env.put(Context.SECURITY_PRINCIPAL, profile.getUserName());
			env.put(Context.SECURITY_CREDENTIALS, Util.decryptPassword(profile.getEncryptedPassword()));
		}
		env.put("com.sun.jndi.ldap.connect.pool", "true");
		env.put("java.naming.ldap.attributes.binary", "objectSid");
		env.put(Context.PROVIDER_URL, profile.getConnectionUrl());
		if (profile.getSsl()) {
			env.put(Context.SECURITY_PROTOCOL, "ssl");
		}

		return new InitialLdapContext(env, null);

	}

	@Override
	public void setCommonProperties(Properties props) {
		commonProp = props;
		
		poolProps.put("com.sun.jndi.ldap.connect.pool.maxsize", props.getProperty("pool_max_size", "20"));
		poolProps.put("com.sun.jndi.ldap.connect.pool.prefsize", props.getProperty("pool_pref_size", "10"));
		poolProps.put("com.sun.jndi.ldap.connect.pool.initsize", props.getProperty("pool_init_size", "1"));
		poolProps.put("com.sun.jndi.ldap.connect.pool.timeout", props.getProperty("pool_time_out", "30000"));
		poolProps.put("com.sun.jndi.ldap.connect.pool.protocol", props.getProperty("pool_protocol", "plain ssl"));

		if (!props.getProperty("pool_debug", "none").equals("none")) {
			poolProps.put("com.sun.jndi.ldap.connect.pool.debug", props.getProperty("pool_debug"));
		}

		poolProps.put("com.sun.jndi.ldap.connect.pool.authentication", "none simple");
		try {
			page_size = Integer.parseInt(props.getProperty("paging_size", "1000"));
		} catch (NumberFormatException nfe) {
			page_size = 1000;
		}

		String keyStoreLocation = props.getProperty("key_store");
		String keyStorePassword = props.getProperty("key_store_pass");
		
		if (keyStoreLocation != null && keyStorePassword != null && keyStoreLocation.length() != 0
				&& keyStorePassword.length() != 0) {
			LOG.info(String.format("Key store is set to %s", keyStoreLocation));
			System.setProperty("javax.net.ssl.keyStore", keyStoreLocation);
			System.setProperty("javax.net.ssl.keyStorePassword", Util.decryptPassword(keyStorePassword));
		} else {
			LOG.info("Key store is not set from properties file");
		}
		
		String trustStoreLocation = props.getProperty("trust_store");
		String trustStorePassword = props.getProperty("trust_store_pass");

		if (trustStoreLocation != null && trustStorePassword != null && trustStoreLocation.length() != 0
				&& trustStorePassword.length() != 0) {
			LOG.info(String.format("Trust store is set to %s", trustStoreLocation));
			System.setProperty("javax.net.ssl.trustStore", trustStoreLocation);
			System.setProperty("javax.net.ssl.trustStorePassword", Util.decryptPassword(trustStorePassword));
		} else {
			LOG.info("Trust store is not set from properties file");
		}

		for (Entry<Object, Object> entry : poolProps.entrySet()) {
			System.setProperty(entry.getKey().toString(), entry.getValue().toString());
		}

		try {
			numberOfRetries = Integer.parseInt(props.getProperty("number_of_retries", "3"));
			intervalBetweenRetries = Integer.parseInt(props.getProperty("interval_between_retries", "30"));
		} catch (NumberFormatException nfe) {
			numberOfRetries = 3;
		}
	}

	@Override
	public PDPObject getPDPObject(String id, String attributeToSearch) throws NamingException {

		PDPObject object = null;

		if (idToObjectTypeMap.get(id) == null
				|| (idToObjectTypeMap.get(id) != null && idToObjectTypeMap.get(id).equals(USER_TYPE))) {

			if (isSingleProfile) {

				object = queryForUser(singleProfile, id);

			} else {

				List<String> profilesToLook = userAttributeToProfileMap.get(attributeToSearch.toLowerCase());
				if (profilesToLook == null) {
					LOG.error(String.format("Attribute [%s] isn't provided by any domain", attributeToSearch));
					return null;
				}

				for (String profileName : profilesToLook) {
					LdapProfile ldapProfile = profiles.get(profileName);

					LOG.info(String.format("Attribute [%s] should be found in domain [%s]. Attemp to query...",
							attributeToSearch, ldapProfile.getName()));

					object = queryForUser(ldapProfile, id);

					if (object != null) {
						break;
					}
				}
			}
		} else if (idToObjectTypeMap.get(id) != null && idToObjectTypeMap.get(id).equals(GROUP_TYPE)) {
			if (isSingleProfile) {

				object = queryForGroup(singleProfile, id);

			} else {

				List<String> profilesToLook = groupAttributeToProfileMap.get(attributeToSearch.toLowerCase());
				if (profilesToLook == null) {
					LOG.error(String.format("Attribute [%s] isn't provided by any domain", attributeToSearch));
					return null;
				}

				for (String profileName : profilesToLook) {
					LdapProfile ldapProfile = profiles.get(profileName);

					LOG.info(String.format("Attribute [%s] should be found in domain [%s]. Attemp to query...",
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
			LOG.error(String.format("Object [%s] cannot be queried from AD", id));
		}

		return object;
	}

	private PDPObject queryForUser(LdapProfile ldapProfile, String userId) throws NamingException {
		PDPObject user = null;

		LdapContext ctx = getContextFromPool(ldapProfile);

		// Create the search controls
		SearchControls searchCtls = new SearchControls();

		List<String> returnedAttrsList = new ArrayList<String>(ldapProfile.getUserKeyAttributes());
		returnedAttrsList.addAll(ldapProfile.getUserAttributesToPull());

		String[] returnedAtts = returnedAttrsList.toArray(new String[0]);
		searchCtls.setReturningAttributes(returnedAtts);

		// Specify the search scope
		searchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);

		// specify the LDAP search filter
		StringBuilder sbFilter = new StringBuilder("(&(");
		sbFilter.append(ldapProfile.getUserSearchFilter());

		if (!ldapProfile.getGetDisabledAccounts()) {
			sbFilter.append(FILTER_DISABLED_ACCOUNTS);
		}

		sbFilter.append("(|");
		for (String key : ldapProfile.getUserKeyAttributes()) {
			sbFilter.append("(");
			sbFilter.append(key);
			sbFilter.append("=");
			sbFilter.append(userId);
			sbFilter.append(")");

		}
		sbFilter.append(")))");

		String searchFilter = sbFilter.toString();

		LOG.debug(String.format("Search filter is [%s]", searchFilter));

		// Specify the Base for the search
		List<String> searchBases = ldapProfile.getUserSearchBase();

		long startTime = System.currentTimeMillis();
		// Search for objects using the filter

		NamingEnumeration<SearchResult> answer = null;
		try {
			for (String searchBase : searchBases) {

				answer = ctx.search(searchBase, searchFilter, searchCtls);

				// Loop through the search results

				while (answer.hasMoreElements()) {
					SearchResult sr = answer.next();
					user = produceUser(sr, ldapProfile);

					// write user to cache
					CacheEngine.getInstance().writeObjectToCache(user);

					// update identifier map
					for (String key : ldapProfile.getUserKeyAttributes()) {
						if (user.getAttribute(key.toLowerCase()) != null
								&& user.getAttribute(key.toLowerCase()).getValue() != null) {
							CacheEngine.getInstance().addIdentifier(
									(String) user.getAttribute(key.toLowerCase()).getValue(), user.getId());
							idToObjectTypeMap.put((String) user.getAttribute(key.toLowerCase()).getValue(), USER_TYPE);
						}
					}

					// user found, break

					break;
				}
			}
		} finally {
			if (answer != null) {
				answer.close();
			}
			if (ctx != null) {
				ctx.close();
			}
		}

		long endTime = System.currentTimeMillis();

		LOG.debug(String.format("Query for user [%s] took %dms", userId, (endTime - startTime)));

		if (answer == null) {
			LOG.error(String.format("Unable to get result from domain [%s]", ldapProfile.getName()));
		}

		return user;
	}

	private PDPObject queryForGroup(LdapProfile ldapProfile, String groupId) throws NamingException {
		PDPObject group = null;

		LdapContext ctx = getContextFromPool(ldapProfile);

		// Create the search controls
		SearchControls searchCtls = new SearchControls();

		List<String> returnedAttrsList = new ArrayList<String>(ldapProfile.getGroupKeyAttributes());
		returnedAttrsList.addAll(ldapProfile.getGroupAttributesToPull());

		String[] returnedAtts = returnedAttrsList.toArray(new String[0]);
		searchCtls.setReturningAttributes(returnedAtts);

		// Specify the search scope
		searchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);

		// specify the LDAP search filter
		StringBuilder sbFilter = new StringBuilder("(&(");
		sbFilter.append(ldapProfile.getGroupSearchFilter());

		if (!ldapProfile.getGetDisabledAccounts()) {
			sbFilter.append(FILTER_DISABLED_ACCOUNTS);
		}

		sbFilter.append("(|");
		for (String key : ldapProfile.getGroupKeyAttributes()) {
			sbFilter.append("(");
			sbFilter.append(key);
			sbFilter.append("=");
			sbFilter.append(groupId);
			sbFilter.append(")");

		}
		sbFilter.append(")))");

		String searchFilter = sbFilter.toString();

		LOG.debug(String.format("Search filter is [%s]", searchFilter));

		NamingEnumeration<SearchResult> answer = null;

		// Specify the Base for the search
		List<String> searchBases = ldapProfile.getGroupSearchBase();

		long startTime = System.currentTimeMillis();

		try {

			for (String searchBase : searchBases) {

				// Search for objects using the filter
				answer = ctx.search(searchBase, searchFilter, searchCtls);

				if (answer == null) {
					LOG.error(String.format("Unable to get result from domain [%s]", ldapProfile.getName()));
				}

				// Loop through the search results

				while (answer.hasMoreElements()) {
					SearchResult sr = answer.next();
					group = produceGroup(sr, ldapProfile);

					// write group to cache
					CacheEngine.getInstance().writeObjectToCache(group);

					// update identifier map
					for (String key : ldapProfile.getGroupKeyAttributes()) {
						if (group.getAttribute(key.toLowerCase()) != null
								&& group.getAttribute(key.toLowerCase()).getValue() != null) {
							CacheEngine.getInstance().addIdentifier(
									(String) group.getAttribute(key.toLowerCase()).getValue(), group.getId());
							idToObjectTypeMap.put((String) group.getAttribute(key.toLowerCase()).getValue(),
									GROUP_TYPE);
						}
					}

					// group found, break

					break;
				}
			}
		} finally {
			if (answer != null) {
				answer.close();
			}
			if (ctx != null) {
				ctx.close();
			}
		}

		long endTime = System.currentTimeMillis();

		LOG.debug(String.format("Query for group [%s] took %dms", groupId, (endTime - startTime)));

		return group;
	}

	@Override
	public synchronized void refreshCache() {
		
		if (!commonProp.getProperty("cache_refresh_period","0").equalsIgnoreCase("0")) {

			isRefreshing = true;

			long startTime = System.currentTimeMillis();

			LOG.info(String.format("Page size is %d", page_size));

			int count = 0;

			while (true) {
				try {
					if (isSingleProfile) {
						refreshProfile(singleProfile);
					} else {
						for (LdapProfile ldapProfile : profiles.values()) {
							refreshProfile(ldapProfile);
						}
					}
					break;

				} catch (Exception e) {

					LOG.error("Cache refresh encountered an exception.", e);

					if (count++ == numberOfRetries) {
						LOG.error(String.format("Attempted [%d] retries without success.", numberOfRetries));
						break;
					} else {
						LOG.debug(String.format("Retrying refreshing cache in [%d] seconds..", intervalBetweenRetries));
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

			LOG.info("Cache refresh completed");
			LOG.info("Time Taken: " + Long.toString((endTime - startTime)) + "ms");

		}
		else{
			LOG.info("Skip reload cache since the cache_refresh_period is 0");
		}
	}

	private void refreshProfile(LdapProfile ldapProfile) throws NamingException {
		LOG.info(String.format("Started refreshing domain [%s]", ldapProfile.getName()));

		if (!ldapProfile.getIsValid()) {
			LOG.error(String.format("Profile [%s] is invalid. Skip refreshing.", ldapProfile.getName()));
			return;
		}

		LdapContext ctx = getContextFromPool(ldapProfile);

		try {
			refreshUser(ldapProfile, ctx);

			if (ldapProfile.getWithGroup()) {
				refreshGroup(ldapProfile, ctx);
			}
		} finally {
			ctx.close();
		}
	}

	private void refreshUser(LdapProfile ldapProfile, LdapContext ctx) throws NamingException {

		NamingEnumeration<SearchResult> answer = null;

		// Create the search controls
		SearchControls searchCtls = new SearchControls();

		List<String> returnedAttrsList = new ArrayList<String>(ldapProfile.getUserKeyAttributes());
		returnedAttrsList.addAll(ldapProfile.getUserAttributesToPull());

		String[] returnedAtts = returnedAttrsList.toArray(new String[0]);
		searchCtls.setReturningAttributes(returnedAtts);

		// Specify the search scope
		searchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);

		// specify the LDAP search filter
		StringBuilder sbFilter = new StringBuilder("(&(");
		sbFilter.append(ldapProfile.getUserSearchFilter());

		if (!ldapProfile.getGetDisabledAccounts()) {
			sbFilter.append(FILTER_DISABLED_ACCOUNTS);
		}

		sbFilter.append("))");

		String searchFilter = sbFilter.toString();

		// Specify the Base for the search
		List<String> searchBases = ldapProfile.getUserSearchBase();

		for (String searchBase : searchBases) {

			int count = 0;

			try {
				byte[] cookie = null;
				ctx.setRequestControls(new Control[] { new PagedResultsControl(page_size, Control.NONCRITICAL) });
				int total;

				do {
					LOG.info("Querying ... ");
					LOG.debug("Search base--" + searchBase.toString() + " | search filter--" + searchFilter +" | search attributes--" + Arrays.toString(returnedAttrsList.toArray()));
					answer = ctx.search(searchBase, searchFilter, searchCtls);

					while (answer.hasMoreElements()) {
						SearchResult sr = (SearchResult) answer.next();
						PDPObject user = produceUser(sr, ldapProfile);

						LOG.debug(String.format("Get from domain [%s] user [%s]", ldapProfile.getName(), user.getId()));

						// write user to cache
						CacheEngine.getInstance().writeObjectToCache(user);

						// update identifier map
						for (String key : ldapProfile.getUserKeyAttributes()) {

							if (user.getAttribute(key.toLowerCase()) != null
									&& user.getAttribute(key.toLowerCase()).getValue() != null) {

								Object keyAttributeValue = user.getAttribute(key.toLowerCase()).getValue();

								LOG.debug(String.format("Put into identifier map [%s] - [%s]",
										(String) keyAttributeValue, user.getId()));

								count++;

								CacheEngine.getInstance().addIdentifier((String) keyAttributeValue, user.getId());

								idToObjectTypeMap.put((String) keyAttributeValue, USER_TYPE);
							}
						}
					}

					// Examine the paged results control response
					Control[] controls = ctx.getResponseControls();
					if (controls != null) {
						for (int i = 0; i < controls.length; i++) {
							if (controls[i] instanceof PagedResultsResponseControl) {
								PagedResultsResponseControl prrc = (PagedResultsResponseControl) controls[i];
								total = prrc.getResultSize();

								if (total != 0) {
									LOG.info(String.format("Total number of users: %d ", total));
								}
								cookie = prrc.getCookie();
							}
						}
					} else {
						LOG.info("No controls were sent from the server");
					}
					// Re-activate paged results
					ctx.setRequestControls(
							new Control[] { new PagedResultsControl(page_size, cookie, Control.CRITICAL) });
				} while (cookie != null && cookie.length != 0);

				LOG.info(String.format("User cache count is [%d]", count));
				System.out.println(String.format("User cache count is [%d]", count));
			} catch (IOException ie) {
				LOG.error(ie.getMessage(), ie);
			} finally {
				if (answer != null) {
					answer.close();
				}
			}
		}
	}

	private void refreshGroup(LdapProfile ldapProfile, LdapContext ctx) throws NamingException {

		NamingEnumeration<SearchResult> answer = null;

		// Create the search controls
		SearchControls searchCtls = new SearchControls();

		List<String> returnedAttrsList = new ArrayList<String>(ldapProfile.getGroupKeyAttributes());
		returnedAttrsList.addAll(ldapProfile.getGroupAttributesToPull());

		String[] returnedAtts = returnedAttrsList.toArray(new String[0]);
		searchCtls.setReturningAttributes(returnedAtts);

		// Specify the search scope
		searchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);

		// specify the LDAP search filter
		StringBuilder sbFilter = new StringBuilder("(&(");
		sbFilter.append(ldapProfile.getGroupSearchFilter());

		if (!ldapProfile.getGetDisabledAccounts()) {
			sbFilter.append(FILTER_DISABLED_ACCOUNTS);
		}

		sbFilter.append("))");

		String searchFilter = sbFilter.toString();

		// Specify the Base for the search
		List<String> searchBases = ldapProfile.getGroupSearchBase();

		for (String searchBase : searchBases) {

			int count = 0;

			try {
				byte[] cookie = null;
				ctx.setRequestControls(new Control[] { new PagedResultsControl(page_size, Control.NONCRITICAL) });
				int total;

				do {
					LOG.info("Querying ... ");
					LOG.debug("Search base--" + searchBase.toString() + " | search filter--" + searchFilter +" | search attributes--" + Arrays.toString(returnedAttrsList.toArray()));
					answer = ctx.search(searchBase, searchFilter, searchCtls);

					while (answer.hasMoreElements()) {
						SearchResult sr = (SearchResult) answer.next();
						PDPObject group = produceGroup(sr, ldapProfile);

						LOG.debug(
								String.format("Get from domain [%s] group [%s]", ldapProfile.getName(), group.getId()));

						// write group to cache
						CacheEngine.getInstance().writeObjectToCache(group);

						// update identifier map
						for (String key : ldapProfile.getGroupKeyAttributes()) {

							if (group.getAttribute(key.toLowerCase()) != null
									&& group.getAttribute(key.toLowerCase()).getValue() != null) {

								Object keyAttributeValue = group.getAttribute(key.toLowerCase()).getValue();

								LOG.debug(String.format("Put into identifier map [%s] - [%s]",
										(String) keyAttributeValue, group.getId()));

								count++;

								CacheEngine.getInstance().addIdentifier((String) keyAttributeValue, group.getId());

								idToObjectTypeMap.put((String) keyAttributeValue, GROUP_TYPE);
							}
						}
					}

					// Examine the paged results control response
					Control[] controls = ctx.getResponseControls();
					if (controls != null) {
						for (int i = 0; i < controls.length; i++) {
							if (controls[i] instanceof PagedResultsResponseControl) {
								PagedResultsResponseControl prrc = (PagedResultsResponseControl) controls[i];
								total = prrc.getResultSize();

								if (total != 0) {
									LOG.info(String.format("Total number of groups : %d ", total));
								}
								cookie = prrc.getCookie();
							}
						}
					} else {
						LOG.info("No controls were sent from the server");
					}
					// Re-activate paged results
					ctx.setRequestControls(
							new Control[] { new PagedResultsControl(page_size, cookie, Control.CRITICAL) });
				} while (cookie != null && cookie.length != 0);

				LOG.info(String.format("Group cache count is [%d]", count));
				System.out.println(String.format("Group cache count is [%d]", count));
			} catch (IOException ie) {
				LOG.error(ie.getMessage(), ie);
			} finally {
				if (answer != null) {
					answer.close();
				}
			}
		}
	}

	private PDPObject produceUser(SearchResult sr, LdapProfile profile) throws NamingException {

		PDPObject user = null;

		Attributes attrs = sr.getAttributes();

		String[] ids = new String[profile.getUserKeyAttributes().size()];

		for (int i = 0; i < profile.getUserKeyAttributes().size(); i++) {

			String keyAttributeName = profile.getUserKeyAttributes().get(i);

			Attribute temp = attrs.get(profile.getUserKeyAttributes().get(i));
			if (temp == null || temp.get() == null) {
				ids[i] = "UNDEFINED";
			} else {
				// object sid data is in binary, need to convert to string
				if (keyAttributeName.trim().equals("objectSid")) {
					ids[i] = convertWindowsSID((byte[]) temp.get());
				} else {
					ids[i] = (String) temp.get();
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

			Attribute temp = attrs.get(attributeName);

			if (!profile.isUserMultiAttribute(attributeName)) {
				user.addAttribute(attributeName.toLowerCase(),
						(temp == null || temp.get() == null) ? EvalValue.NULL : EvalValue.build(temp.get().toString()));
			} else {

				if (temp == null) {
					user.addAttribute(attributeName.toLowerCase(), EvalValue.build(Multivalue.EMPTY));
				} else {

					NamingEnumeration<?> values = temp.getAll();
					sValues.clear();

					while (values.hasMoreElements()) {
						sValues.add((String) values.nextElement());
					}

					if (sValues.size() > 0) {
						user.addAttribute(attributeName.toLowerCase(), EvalValue.build(Multivalue.create(sValues)));
					} else {
						user.addAttribute(attributeName.toLowerCase(), EvalValue.build(Multivalue.EMPTY));
					}
				}
			}

		}

		// process key attributes
		for (int i = 0; i < profile.getUserKeyAttributes().size(); i++) {
			String attributeName = profile.getUserKeyAttributes().get(i);

			Attribute temp = attrs.get(attributeName);

			if (temp != null && temp.get() != null) {

				Object value = temp.get();

				// object sid data is in binary, need to convert to string
				if (attributeName.equals("objectSid")) {
					value = convertWindowsSID((byte[]) value);
				}

				user.addAttribute(attributeName.toLowerCase(),
						EvalValue.build((profile.isUserKeyCaseSensitive(attributeName)) ? value.toString()
								: value.toString().toLowerCase()));
			}
		}

		return user;
	}

	private PDPObject produceGroup(SearchResult sr, LdapProfile profile) throws NamingException {

		PDPObject group = null;

		Attributes attrs = sr.getAttributes();

		String[] ids = new String[profile.getGroupKeyAttributes().size()];

		for (int i = 0; i < profile.getGroupKeyAttributes().size(); i++) {

			String keyAttributeName = profile.getGroupKeyAttributes().get(i);

			Attribute temp = attrs.get(profile.getGroupKeyAttributes().get(i));
			if (temp == null || temp.get() == null) {
				ids[i] = "UNDEFINED";
			} else {
				// object sid data is in binary, need to convert to string
				if (keyAttributeName.trim().equals("objectSid")) {
					ids[i] = convertWindowsSID((byte[]) temp.get());
				} else {
					ids[i] = (String) temp.get();
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

			Attribute temp = attrs.get(attributeName);

			if (!profile.isGroupMultiAttribute(attributeName)) {
				group.addAttribute(attributeName.toLowerCase(),
						(temp == null || temp.get() == null) ? EvalValue.NULL : EvalValue.build(temp.get().toString()));
			} else {

				if (temp == null) {
					group.addAttribute(attributeName.toLowerCase(), EvalValue.build(Multivalue.EMPTY));
				} else {

					NamingEnumeration<?> values = temp.getAll();
					sValues.clear();

					while (values.hasMoreElements()) {
						sValues.add((String) values.nextElement());
					}

					if (sValues.size() > 0) {
						group.addAttribute(attributeName.toLowerCase(), EvalValue.build(Multivalue.create(sValues)));
					} else {
						group.addAttribute(attributeName.toLowerCase(), EvalValue.build(Multivalue.EMPTY));
					}
				}
			}

		}

		// process key attributes
		for (int i = 0; i < profile.getGroupKeyAttributes().size(); i++) {
			String attributeName = profile.getGroupKeyAttributes().get(i);

			Attribute temp = attrs.get(attributeName);

			if (temp != null && temp.get() != null) {

				Object value = temp.get();

				// object sid data is in binary, need to convert to string
				if (attributeName.equals("objectSid")) {
					value = convertWindowsSID((byte[]) value);
				}

				group.addAttribute(attributeName.toLowerCase(),
						EvalValue.build((profile.isGroupKeyCaseSensitive(attributeName)) ? value.toString()
								: value.toString().toLowerCase()));
			}
		}

		return group;
	}

	@Override
	public void loadProfiles(Properties props) {

		profiles = new HashMap<String, LdapProfile>();

		String sProfileNames = props.getProperty("profile_names");

		if (sProfileNames != null) {
			String[] profileNames = sProfileNames.split(",");

			for (String name : profileNames) {

				name = name.trim();
if(props.getProperty(name + "_type")!=null && props.getProperty(name + "_type").equalsIgnoreCase("LDAP") )
{
	isProviderset=true;
}
else
{
	continue;
}

				LOG.info(String.format("Loading profile of domain [%s]", name));
				LdapProfile profile = new LdapProfile(name);
				try {
					profile.parseProfile(props);
					profiles.put(profile.getName(), profile);

					for (String attr : profile.getUserAttributesToPull()) {
						if (userAttributeToProfileMap.containsKey(attr.toLowerCase())) {
							userAttributeToProfileMap.get(attr.toLowerCase()).add(profile.getName());
						} else {
							List<String> newIndex = new ArrayList<String>();
							newIndex.add(profile.getName());
							userAttributeToProfileMap.put(attr.toLowerCase(), newIndex);
						}
					}

					for (String attr : profile.getGroupAttributesToPull()) {
						if (groupAttributeToProfileMap.containsKey(attr.toLowerCase())) {
							groupAttributeToProfileMap.get(attr.toLowerCase()).add(profile.getName());
						} else {
							List<String> newIndex = new ArrayList<String>();
							newIndex.add(profile.getName());
							groupAttributeToProfileMap.put(attr.toLowerCase(), newIndex);
						}
					}

				} catch (InvalidProfileException ipe) {
					LOG.error(String.format("Invalid profile for domain [%s]", name), ipe);
				}

			}
		} else {
			LOG.warn("Profile names are undefined");
		}

	}

	@Override
	public void loadSingleProfile(Properties props) {
		String name = "AD";

		LOG.info(String.format("Loading profile of domain [%s]", name));
		LdapProfile profile = new LdapProfile(name);
		try {
			profile.parseProfile(props);
			singleProfile = profile;
		} catch (InvalidProfileException ipe) {
			LOG.error(String.format("Invalid profile for domain [%s]", name), ipe);
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
			stringSidBuilder.append("0x").append(Long.toHexString(identifierAuthority).toUpperCase());
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
