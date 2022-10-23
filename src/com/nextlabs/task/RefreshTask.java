package com.nextlabs.task;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.nextlabs.ldap.LdapProvider;
import com.nextlabs.ldif.LDIFProvider;

public class RefreshTask implements Runnable {

	private static final Log LOG = LogFactory.getLog(RefreshTask.class);

	private static RefreshTask task;

	public static RefreshTask getInstance() {
		if (task == null) {
			task = new RefreshTask();
		}

		return task;
	}

	@Override
	public void run() {
		LOG.info("Cache refresh started");
if(LdapProvider.getInstance().isProviderset())
		LdapProvider.getInstance().refreshCache();
if(LDIFProvider.getInstance().isProviderset())
	LDIFProvider.getInstance().refreshCache();

		LOG.info("Cache refresh finished");

	}

}
