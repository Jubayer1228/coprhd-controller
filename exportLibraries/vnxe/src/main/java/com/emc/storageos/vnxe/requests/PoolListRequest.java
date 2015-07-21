/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.vnxe.requests;

import java.util.List;

import com.emc.storageos.vnxe.models.VNXePool;

public class PoolListRequest extends KHRequests<VNXePool>{
	private static final String URL = "/api/types/pool/instances";
    public PoolListRequest(KHClient client) {
        super(client);
        _url = URL;
    }


    public List<VNXePool> get(){
        return getDataForObjects(VNXePool.class);

    }

}
