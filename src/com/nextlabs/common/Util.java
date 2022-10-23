package com.nextlabs.common;

import java.net.URLDecoder;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.bluejungle.framework.crypt.IDecryptor;
import com.bluejungle.framework.crypt.IEncryptor;
import com.bluejungle.framework.crypt.ReversibleEncryptor;

public class Util {

	private static String OS = null;
	private static Log LOG = LogFactory.getLog(Util.class);

	public static String getOsName() {
		if (OS == null) {
			OS = System.getProperty("os.name");
		}
		return OS;
	}

	public static boolean isWindows() {
		return getOsName().startsWith("Windows");
	}

	public static String findInstallFolder() {

		String path = Util.class.getProtectionDomain().getCodeSource().getLocation().getPath();

		try {
			path = URLDecoder.decode(path, "UTF-8");

		} catch (Exception e) {
			LOG.error(String.format("Exception while decoding the path: %s", path), e);
		}

		int endIndex = path.indexOf("jservice/jar");

		if (isWindows()) {
			path = path.substring(1, endIndex);
		} else {
			path = path.substring(0, endIndex);
		}
		return path;
	}

	public static String decryptPassword(String encryptedPassword) {
		IDecryptor decryptor = new ReversibleEncryptor();
		return decryptor.decrypt(encryptedPassword);
	}

	public static String encryptPassword(String password) {
		IEncryptor encryptor = new ReversibleEncryptor();
		return encryptor.encrypt(password);
	}

	public static String makeCombinedID(String profileName, String[] ids) {
		Arrays.sort(ids);
		StringBuilder sb = new StringBuilder();
		sb.append(profileName);
		for (int i = 0; i < ids.length; i++) {
			sb.append("#");
			sb.append(ids[i]);
		}
		return sb.toString();
	}

	public static String getProfileFromCombinedId(String combinedId) {
		return combinedId.split("#")[0];
	}
	
	public static void main(String[] args) {
		LOG.info(encryptPassword("123blue!"));
	}
	
	public static String getDurationBreakdown(long millis) {
		if (millis < 0) {
			throw new IllegalArgumentException("Duration must be greater than zero!");
		}

		long days = TimeUnit.MILLISECONDS.toDays(millis);
		millis -= TimeUnit.DAYS.toMillis(days);
		long hours = TimeUnit.MILLISECONDS.toHours(millis);
		millis -= TimeUnit.HOURS.toMillis(hours);
		long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
		millis -= TimeUnit.MINUTES.toMillis(minutes);
		long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);

		StringBuilder sb = new StringBuilder(64);
		sb.append(days);
		sb.append(" Days ");
		sb.append(hours);
		sb.append(" Hours ");
		sb.append(minutes);
		sb.append(" Minutes ");
		sb.append(seconds);
		sb.append(" Seconds");

		return sb.toString();
	}
}
