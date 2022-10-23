package com.nextlabs.common;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class PropertyLoader {

	private static final Log LOG = LogFactory.getLog(PropertyLoader.class);

	public static Properties loadPropertiesInPDP(String filePath) {

		// get the properties file
		/*
		 * String dpcInstallHome = System.getProperty("dpc.install.home"); if
		 * (dpcInstallHome == null || dpcInstallHome.trim().length() < 1) {
		 * dpcInstallHome = "."; }
		 * 
		 * LOG.info("DPC Install Home :" + dpcInstallHome);
		 * 
		 * filePath = dpcInstallHome + filePath;
		 */

		String installLoc = Util.findInstallFolder();

		LOG.info(String.format("Install location is: %s", installLoc));

		if (filePath == null) {
			LOG.error("File path is undefined");
		}

		filePath = installLoc + filePath;

		LOG.info("Properties File Path:: " + filePath);

		Properties result = null;

		try {
			File file = new File(filePath);

			if (file != null) {
				FileInputStream fis = new FileInputStream(file);
				result = new Properties();
				result.load(fis); // Can throw IOException
			}
		} catch (Exception e) {
			LOG.error("Error parsing properties file ", e);
			result = null;
		}
		return result;
	}

	public static Properties loadPropertiesDirectly(String filePath) {
		Properties result = null;
		try {
			File file = new File(filePath);
			LOG.info("Properties File Path:: " + file.getAbsolutePath());
			if (file != null) {
				FileInputStream fis = new FileInputStream(file);
				result = new Properties();
				result.load(fis); // Can throw IOException
			}
		} catch (Exception e) {
			LOG.error("Error parsing properties file ", e);
			result = null;
		}
		return result;
	}
}
