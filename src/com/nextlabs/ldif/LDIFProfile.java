package com.nextlabs.ldif;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.nextlabs.common.Profile;
import com.nextlabs.exception.InvalidProfileException;

public class LDIFProfile extends Profile {

	private String userSearchFilter;
	private String groupSearchFilter;
	private List<String> userKeyAttributes;
	private List<String> userAttributesToPull;
	private List<String> groupKeyAttributes;
	private List<String> groupAttributesToPull;
	private Map<String, Boolean> userCardinalityMap;
	private Map<String, Boolean> groupCardinalityMap;
	private Map<String, Boolean> userKeyCaseSensitiveMap;
	private Map<String, Boolean> groupKeyCaseSensitiveMap;
	private String ldifFilePath;
	private Boolean isValid;
	private Boolean withGroup;

	private static final Log LOG = LogFactory.getLog(LDIFProfile.class);

	public LDIFProfile(String name) {
		super(name);
		userKeyAttributes = new ArrayList<String>();
		userAttributesToPull = new ArrayList<String>();
		groupKeyAttributes = new ArrayList<String>();
		groupAttributesToPull = new ArrayList<String>();
		userCardinalityMap = new HashMap<String, Boolean>();
		groupCardinalityMap = new HashMap<String, Boolean>();
		userKeyCaseSensitiveMap = new HashMap<String, Boolean>();
		groupKeyCaseSensitiveMap = new HashMap<String, Boolean>();
	}

	public void parseProfile(Properties props) throws InvalidProfileException {

		LOG.info(String.format("Started parsing profile for domain [%s]",
				this.name));

		if (props == null) {
			isValid = false;
			throw new InvalidProfileException("Properties is undefined");
		}


		ldifFilePath = getProperty("file_path", props);

		if (ldifFilePath == null || ldifFilePath.length() == 0) {
			isValid = false;
			throw new InvalidProfileException(
					"ldifFilePath is undefined. The plugin cannot initialized to read LDIF entries.");
		}

		

		userSearchFilter = getProperty("user_filter", props);

		if (userSearchFilter == null || userSearchFilter.length() == 0) {
			isValid = false;
			throw new InvalidProfileException(
					"User search filter is undefined. The plugin cannot search for everything.");
		}

		String usk = getProperty("user_key_attributes", props);

		if (usk == null || usk.length() == 0) {
			isValid = false;
			throw new InvalidProfileException("User key attribute is undefined");
		}

		for (String attr : usk.split(",")) {

			String[] pAttr = attr.trim().split(":");

			if (pAttr.length == 2) {
				userKeyAttributes.add(pAttr[1].trim());
				userKeyCaseSensitiveMap.put(pAttr[1],
						(pAttr[0].equals("cs") ? true : false));
			} else {
				LOG.error(String.format("Key attribute [%s] is invalid", attr));
			}
		}

		LOG.info(String.format("User key attributes for domain [%s] are [%s] ",
				name, userKeyAttributes));

		String usa = getProperty("user_attributes_to_pull", props);

		if (usa == null || usa.length() == 0) {
			isValid = false;
			throw new InvalidProfileException("No user attribute to pull");
		}

		for (String attr : usa.split(",")) {
			String[] pAttr = attr.trim().split(":");

			if (pAttr.length == 2) {
				userAttributesToPull.add(pAttr[1].trim());
				userCardinalityMap.put(pAttr[1],
						(pAttr[0].equals("multi") ? true : false));
			} else {
				LOG.error(String.format("Attribute [%s] is invalid", attr));
			}
		}

		LOG.info(String.format(
				"User attributes to pull for domain [%s] are [%s] ", name,
				userAttributesToPull));

		// Config attributes of group

		groupSearchFilter = getProperty("group_filter", props);

		if (groupSearchFilter == null || groupSearchFilter.length() == 0) {
			LOG.info("Domain is not configured to pull group. Skip group configuration");
			withGroup = false;
		} else {

			String gsk = getProperty("group_key_attributes", props);

			if (gsk == null || gsk.length() == 0) {
				throw new InvalidProfileException(
						"Group key attribute is undefined");
			}

			for (String attr : gsk.split(",")) {
				String[] pAttr = attr.trim().split(":");

				if (pAttr.length == 2) {
					groupKeyAttributes.add(pAttr[1].trim());
					groupKeyCaseSensitiveMap.put(pAttr[1],
							(pAttr[0].equals("cs") ? true : false));
				} else {
					LOG.error(String.format("Attribute [%s] is invalid", attr));
				}
			}

			LOG.info(String.format(
					"Group key attributes for domain [%s] are [%s] ", name,
					groupKeyAttributes));

			String gsa = getProperty("group_attributes_to_pull", props);

			if (gsa == null || gsa.length() == 0) {
				isValid = false;
				throw new InvalidProfileException("No group attribute to pull");
			}

			for (String attr : gsa.split(",")) {
				String[] pAttr = attr.trim().split(":");

				if (pAttr.length == 2) {
					groupAttributesToPull.add(pAttr[1].trim());
					groupCardinalityMap.put(pAttr[1],
							(pAttr[0].equals("multi") ? true : false));
				} else {
					LOG.error(String.format("Attribute [%s] is invalid", attr));
				}
			}

			LOG.info(String.format(
					"Group attributes to pull for domain [%s] are [%s] ", name,
					groupAttributesToPull));

			withGroup = true;
		}

		isValid = true;

		LOG.info(String.format("Finished parsing profile for domain [%s]",
				this.name));
	}

	public Boolean isUserMultiAttribute(String attributeName) {
		return (userCardinalityMap.get(attributeName));
	}

	public Boolean isGroupMultiAttribute(String attributeName) {
		return (groupCardinalityMap.get(attributeName));
	}

	public Boolean isUserKeyCaseSensitive(String key) {
		return (userKeyCaseSensitiveMap.get(key));
	}

	public Boolean isGroupKeyCaseSensitive(String key) {
		return (groupKeyCaseSensitiveMap.get(key));
	}

	private String getProperty(String name, Properties props) {
		return props.getProperty(this.name + "_" + name);
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}


	public String getUserSearchFilter() {
		return userSearchFilter;
	}

	public void setUserSearchFilter(String userSearchFilter) {
		this.userSearchFilter = userSearchFilter;
	}

	public String getGroupSearchFilter() {
		return groupSearchFilter;
	}

	public void setGroupSearchFilter(String groupSearchFilter) {
		this.groupSearchFilter = groupSearchFilter;
	}

	public List<String> getUserKeyAttributes() {
		return userKeyAttributes;
	}

	public void setUserKeyAttributes(List<String> userKeyAttributes) {
		this.userKeyAttributes = userKeyAttributes;
	}

	public List<String> getUserAttributesToPull() {
		return userAttributesToPull;
	}

	public void setUserAttributesToPull(List<String> userAttributesToPull) {
		this.userAttributesToPull = userAttributesToPull;
	}

	public List<String> getGroupKeyAttributes() {
		return groupKeyAttributes;
	}

	public void setGroupKeyAttributes(List<String> groupKeyAttributes) {
		this.groupKeyAttributes = groupKeyAttributes;
	}

	public List<String> getGroupAttributesToPull() {
		return groupAttributesToPull;
	}

	public void setGroupAttributesToPull(List<String> groupAttributesToPull) {
		this.groupAttributesToPull = groupAttributesToPull;
	}

	public Map<String, Boolean> getUserCardinalityMap() {
		return userCardinalityMap;
	}

	public void setUserCardinalityMap(Map<String, Boolean> userCardinalityMap) {
		this.userCardinalityMap = userCardinalityMap;
	}

	public Map<String, Boolean> getGroupCardinalityMap() {
		return groupCardinalityMap;
	}

	public void setGroupCardinalityMap(Map<String, Boolean> groupCardinalityMap) {
		this.groupCardinalityMap = groupCardinalityMap;
	}


	public Boolean getIsValid() {
		return isValid;
	}

	public void setIsValid(Boolean isValid) {
		this.isValid = isValid;
	}

	public Boolean getWithGroup() {
		return withGroup;
	}

	public void setWithGroup(Boolean withGroup) {
		this.withGroup = withGroup;
	}

	public String getLdifFilePath() {
		return ldifFilePath;
	}

	public void setLdifFilePath(String ldifFilePath) {
		this.ldifFilePath = ldifFilePath;
	}

}
