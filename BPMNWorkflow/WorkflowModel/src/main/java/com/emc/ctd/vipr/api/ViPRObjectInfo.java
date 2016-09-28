package com.emc.ctd.vipr.api;

import java.io.Serializable;


public  class ViPRObjectInfo implements Serializable{


	private static final long serialVersionUID = 1L;
	private String name;
	private String id;

	

	public ViPRObjectInfo(String name, String id ) {
		
		setId(id);
		setName(name);
		
	}
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

}
