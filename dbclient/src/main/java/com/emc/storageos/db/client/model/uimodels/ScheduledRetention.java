package com.emc.storageos.db.client.model.uimodels;

import java.net.URI;

import com.emc.storageos.db.client.model.Cf;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.Name;
import com.emc.storageos.db.client.model.RelationIndex;
import com.emc.storageos.db.client.model.StringSet;

@Cf("ScheduledEvent")
public class ScheduledRetention extends DataObject {
    public static final String SCHEDULED_EVENT_ID = "scheduledEventId";
    public static final String RESOURCE_ID = "resourceId";
    public static final String RETAINED_RESOURCE_IDS = "retainedResourceIds";
    
    private URI scheduledEventId;
    private URI resourceId;
    private StringSet retainedResourceIds;
    
    @RelationIndex(cf = "RelationIndex", type = ScheduledEvent.class)
    @Name(SCHEDULED_EVENT_ID)
    public URI getScheduledEventId() {
        return scheduledEventId;
    }
    
    public void setScheduledEventId(URI scheduledEventId) {
        this.scheduledEventId = scheduledEventId;
        setChanged(SCHEDULED_EVENT_ID);
    }
    
    @Name(RESOURCE_ID)
    public URI getResourceId() {
        return resourceId;
    }
    
    public void setResourceId(URI resourceId) {
        this.resourceId = resourceId;
        setChanged(RESOURCE_ID);
    }
    
    @Name(RETAINED_RESOURCE_IDS)
    public StringSet getRetainedResourceIds() {
        return retainedResourceIds;
    }
    
    public void setRetainedResourceIds(StringSet retainedResourceIds) {
        this.retainedResourceIds = retainedResourceIds;
        setChanged(RETAINED_RESOURCE_IDS);
    }
    
}
