package com.nextlabs.test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.nextlabs.cache.CacheEngine;
import com.nextlabs.common.PDPObject;
import com.nextlabs.common.PropertyLoader;
import com.nextlabs.common.Util;
import com.nextlabs.ldap.LdapProvider;

public class LdapProviderTestMultiDomain {

	private static final Log LOG = LogFactory.getLog(LdapProviderTestMultiDomain.class);

	public static void main(String[] args) {
		Properties props = PropertyLoader.loadPropertiesDirectly(
				"C:/Git/ServerUserAttributeProvider/etc/ServerUserAttributeProvider-Test.properties");

		LdapProvider provider = LdapProvider.getInstance();
		provider.setIsSingleProfile(false);

		// Test basic profiles

		LOG.info("Testing basic profile....");
		provider.setCommonProperties(props);
		provider.loadProfiles(props);
		LOG.info("Basic profile testing done");
		LOG.info("-------------------------");

		// Test ssl

		LOG.info("Testing SSL connection....");
		try {
			PDPObject user = provider.getPDPObject("jake", "displayName");
			LOG.info(String.format("----Result: %s", user.getAttribute("displayname")));
		} catch (Exception e) {
			e.printStackTrace();
		}

		LOG.info("SSL testing done");
		LOG.info("-------------------------");

		// Test querying

		LOG.info("Testing querying user....");
		try {
			PDPObject user = provider.getPDPObject("jake@qapf1.qalab01.nextlabs.com", "extensionName");
			LOG.info(String.format("----Result: %s", user.getAttribute("displayname")));
			LOG.info(String.format("----Result: %s", user.getAttribute("extensionname")));
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
			provider.getPDPObject("jake@qapf1.qalab01.nextlabs.com", "extensionName");
			provider.getPDPObject("jake", "displayName");
		} catch (Exception e) {
			e.printStackTrace();
		}

		// engine.printCache();

		Runtime rt = Runtime.getRuntime();
		long usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
		LOG.info(">>>>>> Memory used now is " + usedMB);

		try {
			provider.refreshCache();
		} catch (Exception e) {
			e.printStackTrace();
		}

		usedMB = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
		LOG.info(">>>>>> Memory used now is " + usedMB);

		// engine.printCache();
		// engine.printIdentifierMap();

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

		if (engine.getObjectFromCache("bill.clinton") != null) {
			LOG.info("Cache missed successfully after sleeping");
		} else {
			LOG.error("Cache still hit after sleeping. Failed");
		}

		LOG.info("Basic cache testing done");
		LOG.info("-------------------------");

		/*
		 * LOG.info("Testing UserAttributeProvider....");
		 * 
		 * UserAttributeProvider uap = new UserAttributeProvider(props, engine,
		 * provider, 3); for (int i = 0; i < 3; i++) {
		 * LOG.info("Process response called " + (i + 1) + " time(s)");
		 * uap.processResponse("", ""); }
		 * 
		 * IDSubject subject1 = new
		 * DummySubject("jake@qapf1.qalab01.nextlabs.com"); IDSubject subject2 =
		 * new DummySubject("bill.clinton");
		 * 
		 * try { LOG.info("----Result: " + uap.getAttribute(subject2,
		 * "displayName")); LOG.info("----Result: " + uap.getAttribute(subject1,
		 * "extensionName")); } catch (Exception e) { LOG.error(e.getMessage(),
		 * e); }
		 */
		try {
			PDPObject obj = engine.getObjectFromCache("rachel@qapf1.qalab01.nextlabs.com");
			if (obj != null) {
				LOG.info("Cache hit successfully on case sensitivy");
			} else {
				LOG.error("Cache missed on case sensitivity");
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

		try {
			String pattern = props.getProperty("cache_refresh_start_time_format", "dd/MM/yyyy HH:mm:SS");
			String formattedDate = props.getProperty("cache_refresh_start_time");

			Date timeToStart = null;

			if (formattedDate == null) {
				LOG.warn("cache_refresh_start_time is not set. Cache refresh process will be started immediately");
			} else {
				try {
					SimpleDateFormat formatter = new SimpleDateFormat(pattern);
					timeToStart = formatter.parse(formattedDate);
				} catch (Exception e) {
					LOG.error("Cannot parse cache_refresh_start_time. Cache refresh process will be started immediately", e);
				}
			}

			String refreshPeriodString = props.getProperty("cache_refresh_period", "1_DAYS");
			TimeUnit unit = TimeUnit.MILLISECONDS;
			int refreshPeriod;

			String[] temp = refreshPeriodString.split("_");

			try {
				refreshPeriod = Integer.parseInt(temp[0]);
			} catch (IllegalArgumentException e) {
				LOG.error("Invalid cache_refresh_period value(s), resetting to 1_DAYS", e);
				refreshPeriod = 1000 * 60 * 60 * 24;
			}

			try {
				switch (temp[1]) {
				case "SECS":
					refreshPeriod = refreshPeriod*1000;
					break;
				case "MINS":
					refreshPeriod = refreshPeriod * 1000 * 60;
					break;
				case "HRS":
					refreshPeriod = refreshPeriod * 1000 * 60 * 60;
					break;
				case "DAYS":
					refreshPeriod = refreshPeriod * 1000 * 60 * 60 * 24;
					break;
				default:
				}
			} catch (Exception ex) {
				LOG.error("Invalid cache_refresh_period unit, resetting to DAYS", ex);
				refreshPeriod = refreshPeriod * 1000 * 60 * 60 * 24;
			}

			if (timeToStart != null) {
				LOG.info(String.format("Cache refresh start time is set as [%s]", timeToStart.toString()));
			}

			LOG.info(String.format("Cache refresh period is set as [%s]", Util.getDurationBreakdown(refreshPeriod)));

			LOG.info(String.format("Cache refresh process should start after [%s]",
					(timeToStart == null || timeToStart.getTime() - System.currentTimeMillis() < 0) ? (0 + " minute")
							: Util.getDurationBreakdown(timeToStart.getTime() - System.currentTimeMillis())));
		} catch (Exception e) {
			LOG.error(e);
		}

	}
}
