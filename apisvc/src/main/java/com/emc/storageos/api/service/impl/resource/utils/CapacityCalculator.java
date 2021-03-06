/*
 * Copyright (c) 2012 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.api.service.impl.resource.utils;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.Volume;

public interface CapacityCalculator {
    /**
     * Calculates the actual allocated capacity on the storage system
     * for the given requested capacity.
     *
     * @param requestedCapacity the requested volume capacity
     * @param volume the for which we want to calculate the allocated capacity
     * @dbClient the DB client
     * @return the actually array allocated capacity
     */
    public Long calculateAllocatedCapacity(Long requestedCapacity, Volume volume, DbClient dbClient);

    /**
     * Determines if the requested capacity between the storage system
     * passed in and the one invoking this method can match.
     *
     * @param storageSystemType
     * @return Boolean indicating if they can match
     */
    public Boolean capacitiesCanMatch(String storageSystemType);
}
