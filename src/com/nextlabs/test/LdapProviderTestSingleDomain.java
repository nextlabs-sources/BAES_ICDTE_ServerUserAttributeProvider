package com.nextlabs.test;

import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.nextlabs.cache.CacheEngine;
import com.nextlabs.common.PDPObject;
import com.nextlabs.common.PropertyLoader;
import com.nextlabs.ldap.LdapProvider;



public class LdapProviderTestSingleDomain {

	private static final Log LOG = LogFactory.getLog(LdapProviderTestSingleDomain.class);

	public static void main(String[] args) {
		Properties props = PropertyLoader.loadPropertiesDirectly(
				"D:/Git/baes_icdte_serveruserattributeprovider/etc/ServerUserAttributeProvider-SingleDomain.properties");
		
		LdapProvider provider = LdapProvider.getInstance();
		provider.setIsSingleProfile(true);

		// Test basic profiles

		LOG.info("Testing basic profile....");
		provider.setCommonProperties(props);
		provider.loadSingleProfile(props);
		LOG.info("Basic profile testing done");
		LOG.info("-------------------------");

		// Test ssl

		LOG.info("Testing SSL connection....");
		try {
			PDPObject user = provider.getPDPObject("nsadesh", "displayname");
			LOG.info(String.format("----Result: %s", user.getAttribute("displayname")));
		} catch (Exception e) {
			e.printStackTrace();
		}

		LOG.info("SSL testing done");
		LOG.info("-------------------------");

		// Test querying

		LOG.info("Testing querying user....");
		try {
			PDPObject user = provider.getPDPObject("nsadesh@qapf1.qalab01.nextlabs.com", "multiValueTest");
			LOG.info(String.format("----Result: %s", user.getAttribute("displayname")));
			LOG.info(String.format("----Result: %s", user.getAttribute("multiValueTest")));
			LOG.info(String.format("----Result: %s", user.getAttribute("mailnickname")));
		} catch (Exception e) {
			e.printStackTrace();
		}

		LOG.info("Basic user querying done");
		LOG.info("-------------------------");

		// Test cache
		LOG.info("Testing basic cache....");
		CacheEngine engine = CacheEngine.getInstance();
		engine.initializeCache(props);

		try {
			provider.getPDPObject("nsadesh@qapf1.qalab01.nextlabs.com", "multiValueTest");
			provider.getPDPObject("nsadesh", "displayname");
		} catch (Exception e) {
			e.printStackTrace();
		}

		engine.printCache();

		Runtime rt = Runtime.getRuntime();
		long usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
		LOG.info(">>>>>> Memory used now is " + usedMB);

		try {
			provider.refreshCache();
		} catch (Exception e) {
			e.printStackTrace();
		}

		engine.printCache();

		usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
		LOG.info(">>>>>> Memory used now is " + usedMB);

		if (engine.getObjectFromCache("bill.clinton") != null) {
			LOG.info("Cache hit successfully");
		} else {
			LOG.error("Cache missed");
		}

		LOG.info("Waiting for 4 secs now to test cache expiration....");
		try {
			Thread.sleep(4000);
		} catch (InterruptedException ie) {
			LOG.error(ie.getMessage(), ie);
		}

		if (engine.getObjectFromCache("bill.clinton") != null) {
			LOG.info("Cache hit successfully after sleeping");
		} else {
			LOG.error("Cache missed after sleeping. Failed");
		}

		LOG.info("Waiting for 5 secs now to test cache expiration....");
		try {
			Thread.sleep(5000);
		} catch (InterruptedException ie) {
			LOG.error(ie.getMessage(), ie);
		}

		if (engine.getObjectFromCache("bill.clinton") == null) {
			LOG.info("Cache missed successfully after sleeping");
		} else {
			LOG.error("Cache still hit after sleeping. Failed");
		}

		LOG.info("Basic cache testing done");
		LOG.info("-------------------------");

		/*LOG.info("Testing UserAttributeProvider....");

		UserAttributeProvider uap = new UserAttributeProvider(props, engine, provider, 3);
		for (int i = 0; i < 3; i++) {
			LOG.info("Process response called " + (i + 1) + " time(s)");
			uap.processResponse("", "");
		}

		IDSubject subject1 = new DummySubject("jake@qapf1.qalab01.nextlabs.com");
		IDSubject subject2 = new DummySubject("bill.clinton");

		try {
			LOG.info("----Result: " + uap.getAttribute(subject2, "displayName"));
			LOG.info("----Result: " + uap.getAttribute(subject1, "extensionName"));
		} catch (Exception e) {
			LOG.error(e.getMessage(), e);
		}*/
	}
}
