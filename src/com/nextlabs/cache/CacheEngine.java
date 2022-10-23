package com.nextlabs.cache;

import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ehcache.Cache;
import org.ehcache.Cache.Entry;
import org.ehcache.CacheManager;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.config.units.MemoryUnit;
import org.ehcache.expiry.Duration;
import org.ehcache.expiry.Expirations;

import com.nextlabs.common.PDPObject;

public class CacheEngine {

	public static final String CACHE_NAME = "ServerUserAttributeProviderCache";
	private static final Log LOG = LogFactory.getLog(CacheEngine.class);
	private static CacheEngine engine;
	private CacheManager uAPCacheManager;
	private Cache<String, PDPObject> objectCache;
	private Map<String, String> identifierMap;

	public CacheEngine() {
	}

	public static CacheEngine getInstance() {
		if (engine == null) {
			engine = new CacheEngine();
		}
		return engine;
	}

	public void writeObjectToCache(PDPObject obj) {
		if (objectCache == null) {
			LOG.error("Cache has not been initialized");
			return;
		}
		objectCache.put(obj.getId(), obj);
	}

	public void removeObjectFromCache(String objId) {
		String id = identifierMap.get(objId);
		if (id != null) {
			LOG.debug(String.format("Removing object [%s] from cache", objId));
			objectCache.remove(id);
			LOG.debug(String.format("Object [%s] removed from cache", objId));
		} else {
			LOG.warn(String.format("Object [%s] is not in cache. Purge skipped", objId));
		}
	}

	public void initializeCache(Properties props) {
		ResourcePoolsBuilder resourceBuilder = ResourcePoolsBuilder.newResourcePoolsBuilder();
		
		String cacheHeap = props.getProperty("cache_heap_in_mb", "128");
		LOG.info(String.format("cache_heap_in_mb will be set to [%s] MB", cacheHeap)); 
		int iHeapMem = 128;
		
		try{
			iHeapMem = Integer.parseInt(cacheHeap);
		}catch(Exception ex){
			LOG.error("Not able to read cache_heap_in_mb, hard set to 128MB");
		}
		resourceBuilder = resourceBuilder.heap(iHeapMem, MemoryUnit.MB);
		LOG.info(String.format("cache_heap_in_mb will be set to [%s] MB", iHeapMem));
		
		
		String cache_max_object = props.getProperty("cache_max_object","5000");
		long lCache_max_object = 5000;
		try{
			lCache_max_object = Long.parseLong(cache_max_object);
		}catch(Exception ex){
			LOG.error("Not able to read cache_max_object, hard set to 5000");
		}
		
		LOG.info(String.format("cache_max_object will be set to [%s]", lCache_max_object));	

		String timeToLive = props.getProperty("time_to_live", "1_DAYS");
		Duration duration = null;

		if (timeToLive.equals("INFINITE")) {
			duration = Duration.INFINITE;
			LOG.info("Setting time to live to INFINITE");
		} else {

			TimeUnit unit = TimeUnit.DAYS;
			int iTimeToLive = 1;

			String[] temp = timeToLive.split("_");

			try {
				iTimeToLive = Integer.parseInt(temp[0]);
			} catch (IllegalArgumentException e) {
				LOG.error("Invalid time_to_live value(s), resetting to 1_DAYS");
				iTimeToLive = 1;
			}

			try {
				switch (temp[1]) {
				case "SECS":
					unit = TimeUnit.SECONDS;
					break;
				case "MINS":
					unit = TimeUnit.MINUTES;
					break;
				case "HRS":
					unit = TimeUnit.HOURS;
					break;
				case "DAYS":
					unit = TimeUnit.DAYS;
					break;
				default:
				}
			} catch (Exception ex) {
				LOG.error("Invalid time_to_live unit, resetting to DAYS");
				unit = TimeUnit.DAYS;
			}

			duration = Duration.of(iTimeToLive, unit);

			LOG.info(String.format("Setting expiration to %d %s", iTimeToLive, unit.toString()));
		}
		
		CacheConfigurationBuilder<String, PDPObject> cacheConfigurationBuilder = CacheConfigurationBuilder
				.newCacheConfigurationBuilder(String.class, PDPObject.class, resourceBuilder)
				.withExpiry(Expirations.timeToLiveExpiration(duration)).withSizeOfMaxObjectGraph(lCache_max_object);

		CacheManagerBuilder<CacheManager> cacheManagerBuilder = CacheManagerBuilder.newCacheManagerBuilder();
		cacheManagerBuilder = cacheManagerBuilder.withCache(CACHE_NAME, cacheConfigurationBuilder);
		uAPCacheManager = cacheManagerBuilder.build(true);

		objectCache = uAPCacheManager.getCache(CACHE_NAME, String.class, PDPObject.class);

		// identifierMap can be modified concurrently by different requests
		identifierMap = new ConcurrentHashMap<String, String>();
	}

	public PDPObject getObjectFromCache(String id) {
		if (objectCache == null) {
			LOG.error("Cache has not been initialized");
			return null;
		}

		return (identifierMap.get(id) == null) ? null : objectCache.get(identifierMap.get(id));
	}

	public void printCache() {

		if (objectCache == null) {
			LOG.error("Cache has not been initialized");
			return;
		}

		int size = 0;

		Iterator<Entry<String, PDPObject>> it = objectCache.iterator();
		while (it.hasNext()) {
			Entry<String, PDPObject> entry = (Entry<String, PDPObject>) it.next();
			size++;
			LOG.debug(String.format("Cache now contains [%s]", entry.getKey()));
		}

		LOG.debug(String.format("Cache now has [%d] entries", size));
	}

	public void printIdentifierMap() {
		for (Map.Entry<String, String> entry : identifierMap.entrySet()) {
			LOG.info(String.format("Identifier map now contains [%s - %s]", entry.getKey(), entry.getValue()));
		}
	}

	public void addIdentifier(String id, String combinedId) {
		if (identifierMap == null) {
			LOG.error("Cache has not been initialized");
			return;
		}
		identifierMap.put(id, combinedId);
	}
}
