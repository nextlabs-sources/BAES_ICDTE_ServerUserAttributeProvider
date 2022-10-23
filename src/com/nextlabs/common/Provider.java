package com.nextlabs.common;

import java.util.Properties;

public interface Provider {

	public void setCommonProperties(Properties props);

	public PDPObject getPDPObject(String id, String attributeToSearch) throws Exception;

	public void refreshCache();

	public void loadProfiles(Properties props);
	
	public void loadSingleProfile(Properties props);
	
	public void setIsSingleProfile(Boolean isSingleProfile);
	
	public String getPDPObjectType(String id);
	
	public Boolean isRefreshing();

	boolean isProviderset();

	void setProviderset(boolean isProviderset);
}
