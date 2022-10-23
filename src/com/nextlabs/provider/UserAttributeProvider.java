package com.nextlabs.provider;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.bluejungle.framework.expressions.EvalValue;
import com.bluejungle.framework.expressions.IEvalValue;
import com.bluejungle.framework.expressions.IMultivalue;
import com.bluejungle.pf.domain.destiny.serviceprovider.IHeartbeatServiceProvider;
import com.bluejungle.pf.domain.destiny.serviceprovider.ISubjectAttributeProvider;
import com.bluejungle.pf.domain.destiny.serviceprovider.ServiceProviderException;
import com.bluejungle.pf.domain.destiny.subject.IDSubject;
import com.bluejungle.pf.domain.destiny.subject.Subject;
import com.bluejungle.pf.domain.destiny.subject.SubjectType;
import com.nextlabs.cache.CacheEngine;
import com.nextlabs.common.PDPObject;
import com.nextlabs.common.PropertyLoader;
import com.nextlabs.common.Provider;
import com.nextlabs.common.Util;
import com.nextlabs.ldap.LdapProvider;
import com.nextlabs.ldif.LDIFProvider;
import com.nextlabs.task.RefreshTask;

public class UserAttributeProvider implements IHeartbeatServiceProvider,
		ISubjectAttributeProvider {
	private static final Log LOG = LogFactory
			.getLog(UserAttributeProvider.class);
	private Properties PLUGIN_PROPS;
	private final String CLIENT_PROPS_FILE = "jservice/config/ServerUserAttributeProvider.properties";
	private IEvalValue nullReturn;
	CacheEngine engine;
	Provider ldapProvider;
	Provider ldifProvider;



	/*
	 * public UserAttributeProvider(Properties props, CacheEngine engine,
	 * Provider provider, int refreshPeriod) { PLUGIN_PROPS = props; this.engine
	 * = engine; this.provider = provider; this.refreshPeriod = 3; }
	 */

	public void init() {
		long startTime = System.currentTimeMillis();
		LOG.debug("init() started");
		PLUGIN_PROPS = PropertyLoader.loadPropertiesInPDP(CLIENT_PROPS_FILE);

		// Set null return
		String nullString = PLUGIN_PROPS.getProperty("null_string");
		if (nullString == null) {
			nullReturn = EvalValue.NULL;
		} else {
			nullReturn = EvalValue.build(nullString);
		}

		// Initialize Cache
		engine = CacheEngine.getInstance();
		engine.initializeCache(PLUGIN_PROPS);

		// for now hardcoded to LDAP provider, can introduce new property to
		// dynamically choose another provider in the future
		// provider = LdapProvider.getInstance();
		ldapProvider = LdapProvider.getInstance();
		ldifProvider = LDIFProvider.getInstance();
		ldapProvider.setCommonProperties(PLUGIN_PROPS);
		ldifProvider.setCommonProperties(PLUGIN_PROPS);
		ldifProvider.setProviderset(false);
		ldapProvider.setProviderset(false);
		
		if (PLUGIN_PROPS.getProperty("profile_names") == null
				|| PLUGIN_PROPS.getProperty("profile_names").length() == 0) {
			if (PLUGIN_PROPS.getProperty("LDIF_type") == null) {
				ldapProvider.setIsSingleProfile(true);
				ldapProvider.loadSingleProfile(PLUGIN_PROPS);
				ldifProvider.setProviderset(true);
			}else
			{
				ldifProvider.setIsSingleProfile(true);
				ldifProvider.loadSingleProfile(PLUGIN_PROPS);
				ldapProvider.setProviderset(true);
			}
		} else {
			ldapProvider.setIsSingleProfile(false);
			ldapProvider.loadProfiles(PLUGIN_PROPS);
			ldifProvider.setIsSingleProfile(false);
			ldifProvider.loadProfiles(PLUGIN_PROPS);
		}

		try {
			if(ldapProvider.isProviderset())
			ldapProvider.refreshCache();
			if(ldifProvider.isProviderset())
				ldifProvider.refreshCache();
		} catch (Exception e) {
			LOG.error(e.getMessage(), e);
		}

		try {
			scheduleTimer();
		} catch (Exception e) {
			LOG.error(e.getMessage(), e);
		}

		LOG.debug("init() finished");

		long endTime = System.currentTimeMillis();
		LOG.info("Time Taken: " + Long.toString((endTime - startTime)) + "ms");
	}

	private void scheduleTimer() {

		String refreshPeriodString = PLUGIN_PROPS.getProperty(
				"cache_refresh_period", "1_DAYS");

		if (!refreshPeriodString.equalsIgnoreCase("0")) {

			String pattern = PLUGIN_PROPS.getProperty(
					"cache_refresh_start_time_format", "dd/MM/yyyy HH:mm:SS");
			String formattedDate = PLUGIN_PROPS
					.getProperty("cache_refresh_start_time");

			Date timeToStart = null;

			if (formattedDate == null) {
				LOG.warn("cache_refresh_start_time is not set. Cache refresh process will be started immediately");
			} else {
				try {
					SimpleDateFormat formatter = new SimpleDateFormat(pattern);
					timeToStart = formatter.parse(formattedDate);
				} catch (Exception e) {
					LOG.error(
							"Cannot parse cache_refresh_start_time. Cache refresh process will be started immediately",
							e);
				}
			}

			TimeUnit unit = TimeUnit.MILLISECONDS;
			int refreshPeriod;

			String[] temp = refreshPeriodString.split("_");

			try {
				refreshPeriod = Integer.parseInt(temp[0]);
			} catch (IllegalArgumentException e) {
				LOG.error(
						"Invalid cache_refresh_period value(s), resetting to 1_DAYS",
						e);
				refreshPeriod = 1000 * 60 * 60 * 24;
			}

			try {
				switch (temp[1]) {
				case "SECS":
					refreshPeriod = refreshPeriod * 1000;
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
				LOG.error(
						"Invalid cache_refresh_period unit, resetting to DAYS",
						ex);
				refreshPeriod = refreshPeriod * 1000 * 60 * 60 * 24;
			}

			if (timeToStart != null) {
				LOG.info(String.format("Cache refresh period is set as [%s]",
						Util.getDurationBreakdown(refreshPeriod)));
			}

			LOG.info(String.format(
					"Cache refresh process should start after [%s]",
					(timeToStart == null || timeToStart.getTime()
							- System.currentTimeMillis() < 0) ? (0 + " minute")
							: Util.getDurationBreakdown(timeToStart.getTime()
									- System.currentTimeMillis())));

			ScheduledExecutorService service = Executors
					.newScheduledThreadPool(1);
			service.scheduleAtFixedRate(
					RefreshTask.getInstance(),
					(timeToStart == null) ? 0 : (timeToStart.getTime() - System
							.currentTimeMillis()), refreshPeriod, unit);
			LOG.info("Cache refresh process has been scheduled");

		}
	}

	@Override
	public Serializable prepareRequest(String id) {
		return null;
	}

	@Override
	public void processResponse(String id, String data) {
		return;
	}

	public synchronized IEvalValue getAttribute(IDSubject subj, String attribute)
			throws ServiceProviderException {
		long startTime = System.currentTimeMillis();
		String id = subj.getUid();

		if (subj.getAttribute("purge") != null
				&& subj.getAttribute("purge").getValue() != null
				&& subj.getAttribute("purge").getValue().equals("yes")) {

			if (id.equalsIgnoreCase("all")) {
				LOG.info("Request for refreshing cache received");
				if (ldapProvider.isRefreshing()) {
					LOG.error("Refresh is currently running. Please wait until the current refresh has finished");
				} else {
					if(ldapProvider.isProviderset())
					ldapProvider.refreshCache();
					if(ldifProvider.isProviderset())
						ldifProvider.refreshCache();
				}
			} else {
				LOG.info("Request for purging object received");
				engine.removeObjectFromCache(id);
			}
			return EvalValue.NULL;
		}

		String type=null;
		if(ldapProvider.isProviderset())
		 type = ldapProvider.getPDPObjectType(id);
		if(type!=null && ldifProvider.isProviderset())
			 type = ldifProvider.getPDPObjectType(id);
		if (attribute.equalsIgnoreCase("recipient-type")) {
			if (type != null) {
				LOG.info(String
						.format("[%s] has attribute [recipient-type] with value = [%s]",
								id, type));
				return EvalValue.build(type);
			} else {
				LOG.info(String.format("Cannot find recipient-type for [%s]",
						id));
				return nullReturn;
			}
		}

		LOG.debug(String.format("Type of object is [%s]", type));

		LOG.debug(String.format("Getting attribute [%s] for [%s] [%s]",
				attribute.toLowerCase(), type, id));

		PDPObject obj = engine.getObjectFromCache(id);

		// try again with case insensitive
		if (obj == null) {
			obj = engine.getObjectFromCache(id.toLowerCase());
		}

		// cache doesn't contain the user, query from AD
		if (obj == null) {
			LOG.info(String.format(
					"Cache missed for [%s]. Attempt to query...", id));
			engine.printCache();

			try {
				if(ldapProvider.isProviderset())
					obj = ldapProvider.getPDPObject(id, attribute.toLowerCase());
					if(type!=null && ldifProvider.isProviderset())
						obj = ldifProvider.getPDPObject(id, attribute.toLowerCase());
			} catch (Exception e) {
				LOG.error(String.format("Unable to query for [%s]", type));
				LOG.error(e.getMessage(), e);
				return nullReturn;
			}
		}

		if (obj == null) {
			LOG.error(String.format(
					"Cannot resolve attribute [%s] for [%s] [%s].", attribute,
					type, id));
			return nullReturn;
		}

		IEvalValue val = obj.getAttribute(attribute.toLowerCase());

		if (val == null || val.getValue() == null) {
			LOG.info(String.format("Attribute [%s] is null for [%s] [%s]",
					attribute, type, id));
			val = nullReturn;
		}

		if (val.getValue() instanceof IMultivalue) {
			StringBuilder sb = new StringBuilder("[" + id + "] has attribute ["
					+ attribute + "] with value = ");

			boolean first = true;
			for (IEvalValue v : (IMultivalue) val.getValue()) {
				if (!first) {
					sb.append(", ");
				}
				first = false;
				if (v == null) {
					sb.append("null");
				} else {
					sb.append(v.getValue());
				}
			}
			LOG.info(sb.toString());
		} else {
			LOG.info(String.format("[%s] has attribute [%s] with value = [%s]",
					id, attribute, val.getValue()));
		}

		long endTime = System.currentTimeMillis();
		LOG.info(String.format("Time Taken: %sms",
				Long.toString((endTime - startTime))));
		return val;
	}

	public static void main(String args[]) {
		UserAttributeProvider uap = new UserAttributeProvider();
		uap.init();
	
		Subject subject=new Subject("AHMADNU3","AHMADNU3","AHMADNU3",null, SubjectType.USER);
		
		System.out.println(subject.getUid());
		try {
			Thread.sleep(1000);
			System.out.println(uap.getAttribute(subject, "SITE_CODE"));
		} catch (ServiceProviderException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}
}
