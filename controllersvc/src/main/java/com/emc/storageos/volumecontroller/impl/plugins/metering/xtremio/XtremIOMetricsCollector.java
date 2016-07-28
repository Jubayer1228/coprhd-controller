/*
 * Copyright (c) 2016 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.volumecontroller.impl.plugins.metering.xtremio;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.StorageHADomain;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.plugins.common.Constants;
import com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator;
import com.emc.storageos.volumecontroller.impl.plugins.metering.smis.processor.MetricsKeys;
import com.emc.storageos.volumecontroller.impl.plugins.metering.smis.processor.PortMetricsProcessor;
import com.emc.storageos.volumecontroller.impl.xtremio.prov.utils.XtremIOProvUtils;
import com.emc.storageos.xtremio.restapi.XtremIOClient;
import com.emc.storageos.xtremio.restapi.XtremIOClientFactory;
import com.emc.storageos.xtremio.restapi.XtremIOConstants;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOPerformanceResponse;
import com.google.common.collect.ArrayListMultimap;

public class XtremIOMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(XtremIOMetricsCollector.class);

    private XtremIOClientFactory xtremioRestClientFactory;
    private PortMetricsProcessor portMetricsProcessor;

    public void setXtremioRestClientFactory(XtremIOClientFactory xtremioRestClientFactory) {
        this.xtremioRestClientFactory = xtremioRestClientFactory;
    }

    public void setPortMetricsProcessor(PortMetricsProcessor portMetricsProcessor) {
        this.portMetricsProcessor = portMetricsProcessor;
    }

    /**
     * Collect metrics.
     *
     * @param system the system
     * @param dbClient the db client
     * @throws Exception
     */
    public void collectMetrics(StorageSystem system, DbClient dbClient) throws Exception {
        log.info("Collecting statistics for XtremIO system {}", system.getNativeGuid());
        XtremIOClient xtremIOClient = XtremIOProvUtils.getXtremIOClient(dbClient, system, xtremioRestClientFactory);
        String xtremIOClusterName = xtremIOClient.getClusterDetails(system.getSerialNumber()).getName();

        // TODO Full support for Metering collection.
        // Currently only the XEnv's CPU Utilization will be collected and
        // used for resource placement to choose the best XtremIO Cluster.
        // Reason for CPU over port metrics: Some port metrics like KBytesTransferred are not available for XtremIO.
        // XtremIO team also suggested to consider CPU usage over IOPs, Bandwidth, Latency.
        collectXEnvCPUUtilization(system, dbClient, xtremIOClient, xtremIOClusterName);
    }

    /**
     * Collect the CPU Utilization for all XEnv's in the cluster.
     *
     * @param system the system
     * @param dbClient the db client
     * @param xtremIOClient the xtremio client
     * @param xioClusterName the xtremio cluster name
     * @throws Exception
     */
    private void collectXEnvCPUUtilization(StorageSystem system, DbClient dbClient,
            XtremIOClient xtremIOClient, String xtremIOClusterName) throws Exception {
        // An XENV(XtremIO Environment) is composed of software defined modules responsible for internal data path on the array.
        // There are two CPU sockets per Storage Controller (SC), and one distinct XENV runs on each socket.
        /**
         * Collect average CPU usage:
         * - Get the last processing time for the system,
         * - If previously not queried or if it was long back, collect data for last one day
         * 
         * - Query the XEnv metrics from from-time to current-time with granularity based on cycle time gap
         * - 1. Group the XEnvs by SC,
         * - 2. For each SC:
         * - - - Take the average of 2 XEnv's CPU usages
         * - - - Calculate exponential average by calling PortMetricsProcessor.processFEAdaptMetrics()
         * - - - - (persists cpuPercentBusy, emaPercentBusy and avgCpuPercentBusy)
         * 
         * - Average of all SC's avgCpuPercentBusy values is the average CPU usage for the system
         */

        log.info("Collecting CPU usage for XtremIO system {}", system.getNativeGuid());
        Long lastProcessedTime = system.getLastMeteringRunTime();
        Long currentTime = System.currentTimeMillis();
        Long oneDayTime = TimeUnit.DAYS.toMillis(1);
        if (lastProcessedTime < 0 || ((currentTime - lastProcessedTime) > oneDayTime)) {
            lastProcessedTime = currentTime - oneDayTime;   // last 1 day
        }
        // granularity is kept as 1 hour so that the minimum granular data obtained is hourly basis
        String granularity = getGranularity(lastProcessedTime, currentTime);
        SimpleDateFormat format = new SimpleDateFormat(XtremIOConstants.DATE_FORMAT);
        String fromTime = format.format(new Date(lastProcessedTime));
        String toTime = format.format(new Date(currentTime));

        XtremIOPerformanceResponse response = xtremIOClient.getXtremIOObjectPerformance(xtremIOClusterName,
                XtremIOConstants.XTREMIO_ENTITY_TYPE.XEnv.name(), XtremIOConstants.FROM_TIME, fromTime,
                XtremIOConstants.TO_TIME, toTime, XtremIOConstants.GRANULARITY, granularity);
        log.info("Response - Members: {}", Arrays.toString(response.getMembers()));
        log.info("Response - Counters: {}", Arrays.deepToString(response.getCounters()));

        // Segregate the responses by XEnv
        ArrayListMultimap<String, Integer> xEnvToCPUvalues = ArrayListMultimap.create();
        int xEnvIndex = getIndexForAttribute(response.getMembers(), XtremIOConstants.NAME);
        int cpuIndex = getIndexForAttribute(response.getMembers(), XtremIOConstants.AVG_CPU_USAGE);
        String[][] counters = response.getCounters();
        for (String[] counter : counters) {
            log.debug(Arrays.toString(counter));
            String xEnv = counter[xEnvIndex];
            String cpuUtilization = counter[cpuIndex];
            if (cpuUtilization != null) {
                xEnvToCPUvalues.put(xEnv, Integer.valueOf(cpuUtilization));
            }
        }

        // calculate the average usage for each XEnv for the queried period of time
        Map<String, Double> xEnvToAvgCPU = new HashMap<>();
        for (String xEnv : xEnvToCPUvalues.keySet()) {
            List<Integer> cpuUsageList = xEnvToCPUvalues.get(xEnv);
            Double avgCPU = (double) (cpuUsageList.stream().mapToInt(Integer::intValue).sum() / cpuUsageList.size());
            log.info("XEnv: {}, collected CPU usage: {}, average: {}", xEnv, cpuUsageList.toString(), avgCPU);
            xEnvToAvgCPU.put(xEnv, avgCPU);
        }

        // calculate the average usage for each Storage controller (from it's 2 XEnvs)
        Map<StorageHADomain, Double> scToAvgCPU = new HashMap<>();
        for (String xEnv : xEnvToAvgCPU.keySet()) {
            StorageHADomain sc = getStorageControllerForXEnv(xEnv, system, dbClient);
            Double scCPU = scToAvgCPU.get(sc);
            Double xEnvCPU = xEnvToAvgCPU.get(xEnv);
            Double avgScCPU = (scCPU == null) ? xEnvCPU : ((xEnvCPU + scCPU) / 2.0);
            scToAvgCPU.put(sc, avgScCPU);
        }

        // calculate exponential average for each Storage controller
        for (StorageHADomain sc : scToAvgCPU.keySet()) {
            Double avgScCPU = scToAvgCPU.get(sc);
            log.info("StorageHADomain: {}, average CPU Usage: {}", sc.getAdapterName(), avgScCPU);

            portMetricsProcessor.processFEAdaptMetrics(avgScCPU, 0l, sc, currentTime.toString(), false);

            StringMap dbMetrics = sc.getMetrics();
            double emaFactor = PortMetricsProcessor.getEmaFactor(DiscoveredDataObject.Type.valueOf(system.getSystemType()));
            if (emaFactor > 1.0) {
                emaFactor = 1.0;  // in case of invalid user input
            }
            Double scAvgBusy = MetricsKeys.getDouble(MetricsKeys.avgPercentBusy, dbMetrics);
            Double scEmaBusy = MetricsKeys.getDouble(MetricsKeys.emaPercentBusy, dbMetrics);
            Double scPercentBusy = (scAvgBusy * emaFactor) + ((1 - emaFactor) * scEmaBusy);
            MetricsKeys.putDouble(MetricsKeys.avgCpuPercentBusy, scPercentBusy, dbMetrics);
            MetricsKeys.putLong(MetricsKeys.lastProcessingTime, currentTime, dbMetrics);
            sc.setMetrics(dbMetrics);
            dbClient.updateObject(sc);
        }

        // calculate storage system's average CPU usage by combining all XEnvs
        portMetricsProcessor.computeStorageSystemAvgPortMetrics(system.getId());
    }

    /**
     * Gets the storage controller (StorageHADomain) for the given XEnv name.
     */
    private StorageHADomain getStorageControllerForXEnv(String xEnv, StorageSystem system, DbClient dbClient) {
        StorageHADomain haDomain = null;
        String haDomainNativeGUID = NativeGUIDGenerator.generateNativeGuid(system,
                xEnv.substring(0, xEnv.lastIndexOf(Constants.HYPHEN) - 1), NativeGUIDGenerator.ADAPTER);
        URIQueryResultList haDomainQueryResult = new URIQueryResultList();
        dbClient.queryByConstraint(AlternateIdConstraint.Factory.getStorageHADomainByNativeGuidConstraint(haDomainNativeGUID),
                haDomainQueryResult);
        Iterator<URI> itr = haDomainQueryResult.iterator();
        if (itr.hasNext()) {
            haDomain = dbClient.queryObject(StorageHADomain.class, itr.next());
        }
        return haDomain;
    }

    /**
     * Get the granularity for the performance query based on the time gap.
     * Values: one_day, one_hour, one_minute, ten_minutes
     *
     * @param fromTime the last processed time
     * @param toTime the current time
     * @return the granularity
     */
    private String getGranularity(Long fromTime, Long toTime) {
        Long timeGap = toTime - fromTime;
        String granularity = XtremIOConstants.ONE_HOUR;    // default
        if (timeGap > TimeUnit.DAYS.toMillis(1)) {  // more than a day
            granularity = XtremIOConstants.ONE_DAY;
        } else {
            if (timeGap < TimeUnit.HOURS.toMillis(1)) {  // less than an hour
                granularity = XtremIOConstants.TEN_MINUTES;
            }
            if (timeGap < TimeUnit.MINUTES.toMillis(10)) {  // less than 10 minutes
                granularity = XtremIOConstants.ONE_MINUTE;
            }
        }
        return granularity;
    }

    /**
     * Get the location index in the array for the given string.
     */
    private int getIndexForAttribute(String[] members, String name) {
        for (int index = 0; index < members.length; index++) {
            if (name != null && name.equalsIgnoreCase(members[index])) {
                return index;
            }
        }
        return 0;
    }
}
