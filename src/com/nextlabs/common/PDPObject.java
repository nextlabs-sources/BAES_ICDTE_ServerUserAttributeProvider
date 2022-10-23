package com.nextlabs.common;

import java.util.HashMap;
import java.util.Map;

import com.bluejungle.framework.expressions.IEvalValue;

public class PDPObject {
	private String id;
	private Map<String, IEvalValue> attributes;
	private String domain;
	private String type;

	public PDPObject(String domain, String id, String type) {
		this.id = id;
		this.domain = domain;
		attributes = new HashMap<String, IEvalValue>();
		this.type = type;
	}

	public void addAttribute(String key, IEvalValue value) {
		attributes.put(key, value);
	}

	public IEvalValue getAttribute(String key) {
		return attributes.get(key);
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public Map<String, IEvalValue> getAttributes() {
		return attributes;
	}

	public void setAttributes(Map<String, IEvalValue> attributes) {
		this.attributes = attributes;
	}

	public String getDomain() {
		return domain;
	}

	public void setDomain(String domain) {
		this.domain = domain;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

}
