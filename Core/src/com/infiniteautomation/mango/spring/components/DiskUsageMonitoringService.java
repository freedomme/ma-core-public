/**
 * Copyright (C) 2019  Infinite Automation Software. All rights reserved.
 */
package com.infiniteautomation.mango.spring.components;

import java.io.File;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.infiniteautomation.mango.monitor.MonitoredValues;
import com.infiniteautomation.mango.monitor.PollableMonitor;
import com.infiniteautomation.mango.monitor.ValueMonitor;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.db.NoSQLProxy;
import com.serotonin.m2m2.i18n.TranslatableMessage;
import com.serotonin.m2m2.module.FileStoreDefinition;
import com.serotonin.m2m2.module.ModuleRegistry;

/**
 * Monitor partition sizes and optionally file stores and ma_home directories
 *
 * @author Terry Packer
 *
 */
@Service
public class DiskUsageMonitoringService {

    private final Log log = LogFactory.getLog(DiskUsageMonitoringService.class);

    public static final String MA_HOME_PARTITION_TOTAL_SPACE = "internal.monitor.MA_HOME_PARTITION_TOTAL_SPACE";
    public static final String MA_HOME_PARTITION_USABLE_SPACE = "internal.monitor.MA_HOME_PARTITION_USABLE_SPACE";
    public static final String MA_HOME_PARTITION_USED_SPACE = "internal.monitor.MA_HOME_PARTITION_USED_SPACE";

    public static final String NOSQL_PARTITION_TOTAL_SPACE = "internal.monitor.NOSQL_PARTITION_TOTAL_SPACE";
    public static final String NOSQL_PARTITION_USABLE_SPACE = "internal.monitor.NOSQL_PARTITION_USABLE_SPACE";
    public static final String NOSQL_PARTITION_USED_SPACE = "internal.monitor.NOSQL_PARTITION_USED_SPACE";
    public static final String NOSQL_DATABASE_SIZE = "internal.monitor.NOSQL_DATABASE_SIZE";

    public static final String SQL_PARTITION_TOTAL_SPACE = "internal.monitor.SQL_PARTITION_TOTAL_SPACE";
    public static final String SQL_PARTITION_USABLE_SPACE = "internal.monitor.SQL_PARTITION_USABLE_SPACE";
    public static final String SQL_PARTITION_USED_SPACE = "internal.monitor.SQL_PARTITION_USED_SPACE";
    public static final String SQL_DATABASE_SIZE = "internal.monitor.SQL_DATABASE_SIZE";

    public static final String FILESTORE_PARTITION_TOTAL_SPACE = "internal.monitor.FILESTORE_PARTITION_TOTAL_SPACE";
    public static final String FILESTORE_PARTITION_USABLE_SPACE = "internal.monitor.FILESTORE_PARTITION_USABLE_SPACE";
    public static final String FILESTORE_PARTITION_USED_SPACE = "internal.monitor.FILESTORE_PARTITION_USED_SPACE";

    public static final String MA_HOME_SIZE = "internal.monitor.MA_HOME_SIZE";
    public static final String FILE_STORE_SIZE_PREFIX = "internal.monitor.FILE_STORE.";

    private final ExecutorService executor;
    private final ScheduledExecutorService scheduledExecutor;
    private final long period;

    private final ValueMonitor<Double> maHomePartitionTotalSpace;
    private final ValueMonitor<Double> maHomePartitionUsableSpace;
    private final ValueMonitor<Double> maHomePartitionUsedSpace;
    private final ValueMonitor<Double> noSqlPartitionTotalSpace;
    private final ValueMonitor<Double> noSqlPartitionUsableSpace;
    private final ValueMonitor<Double> noSqlPartitionUsedSpace;
    private final PollableMonitor<Double> noSqlDatabaseSize;
    private final ValueMonitor<Double> sqlPartitionTotalSpace;
    private final ValueMonitor<Double> sqlPartitionUsableSpace;
    private final ValueMonitor<Double> sqlPartitionUsedSpace;
    private final PollableMonitor<Double> sqlDatabaseSize;
    private final ValueMonitor<Double> filestorePartitionTotalSpace;
    private final ValueMonitor<Double> filestorePartitionUsableSpace;
    private final ValueMonitor<Double> filestorePartitionUsedSpace;
    private final ValueMonitor<Double> maHomeSize;

    private final Map<String, ValueMonitor<Double>> fileStoreMonitors = new HashMap<>();
    private volatile ScheduledFuture<?> scheduledFuture;

    private final double gb = 1024*1024*1024;

    //NoSQL disk space
    private Double lastNoSqlDatabaseSize;
    private long lastNoSqlDatabaseSizePollTime;
    private static final long MIN_NOSQL_DISK_SIZE_POLL_PERIOD = 1000 * 60 * 60 * 6;

    //SQL disk space
    private Double lastSqlDatabaseSize;
    private long lastSqlDatabaseSizePollTime;
    private static final long MIN_SQL_DISK_SIZE_POLL_PERIOD = 1000 * 60 * 60 * 6;

    /**
     *
     * @param scheduledExecutor
     * @param period - how often to recalculate the usage
     * @param monitorDirectories - monitor file stores and ma_home specifically (will require more CPU)
     */
    @Autowired
    private DiskUsageMonitoringService(ExecutorService executor,
            ScheduledExecutorService scheduledExecutor,
            @Value("${internal.monitor.diskUsage.pollPeriod:1800000}") Long period,
            @Value("${internal.monitor.diskUsage.monitorDirectories:false}") boolean monitorDirectories,
            MonitoredValues monitoredValues) {

        this.executor = executor;
        this.scheduledExecutor = scheduledExecutor;
        this.period = period;

        maHomePartitionTotalSpace = monitoredValues.<Double>create(MA_HOME_PARTITION_TOTAL_SPACE)
                .name(new TranslatableMessage(MA_HOME_PARTITION_TOTAL_SPACE))
                .build();
        maHomePartitionUsableSpace = monitoredValues.<Double>create(MA_HOME_PARTITION_USABLE_SPACE)
                .name(new TranslatableMessage(MA_HOME_PARTITION_USABLE_SPACE))
                .build();
        maHomePartitionUsedSpace = monitoredValues.<Double>create(MA_HOME_PARTITION_USED_SPACE)
                .name(new TranslatableMessage(MA_HOME_PARTITION_USED_SPACE))
                .build();
        noSqlPartitionTotalSpace = monitoredValues.<Double>create(NOSQL_PARTITION_TOTAL_SPACE)
                .name(new TranslatableMessage(NOSQL_PARTITION_TOTAL_SPACE))
                .build();
        noSqlPartitionUsableSpace = monitoredValues.<Double>create(NOSQL_PARTITION_USABLE_SPACE)
                .name(new TranslatableMessage(NOSQL_PARTITION_USABLE_SPACE))
                .build();
        noSqlPartitionUsedSpace = monitoredValues.<Double>create(NOSQL_PARTITION_USED_SPACE)
                .name(new TranslatableMessage(NOSQL_PARTITION_USED_SPACE))
                .build();
        noSqlDatabaseSize = monitoredValues.<Double>create(NOSQL_DATABASE_SIZE).name(new TranslatableMessage(NOSQL_DATABASE_SIZE)).supplier(this::getNoSqlDatabaseSizeMB).buildPollable();

        sqlPartitionTotalSpace = monitoredValues.<Double>create(SQL_PARTITION_TOTAL_SPACE)
                .name(new TranslatableMessage(SQL_PARTITION_TOTAL_SPACE))
                .build();
        sqlPartitionUsableSpace = monitoredValues.<Double>create(SQL_PARTITION_USABLE_SPACE)
                .name(new TranslatableMessage(SQL_PARTITION_USABLE_SPACE))
                .build();
        sqlPartitionUsedSpace = monitoredValues.<Double>create(SQL_PARTITION_USED_SPACE)
                .name(new TranslatableMessage(SQL_PARTITION_USED_SPACE))
                .build();
        sqlDatabaseSize = monitoredValues.<Double>create(SQL_DATABASE_SIZE).name(new TranslatableMessage(SQL_DATABASE_SIZE)).supplier(this::getSqlDatabaseSizeMB).buildPollable();

        filestorePartitionTotalSpace = monitoredValues.<Double>create(FILESTORE_PARTITION_TOTAL_SPACE)
                .name(new TranslatableMessage(FILESTORE_PARTITION_TOTAL_SPACE))
                .build();
        filestorePartitionUsableSpace = monitoredValues.<Double>create(FILESTORE_PARTITION_USABLE_SPACE)
                .name(new TranslatableMessage(FILESTORE_PARTITION_USABLE_SPACE))
                .build();
        filestorePartitionUsedSpace = monitoredValues.<Double>create(FILESTORE_PARTITION_USED_SPACE)
                .name(new TranslatableMessage(FILESTORE_PARTITION_USED_SPACE))
                .build();

        if (monitorDirectories) {
            maHomeSize = monitoredValues.<Double>create(MA_HOME_SIZE)
                    .name(new TranslatableMessage(MA_HOME_SIZE))
                    .build();

            //Setup all filestores
            ModuleRegistry.getFileStoreDefinitions().values().stream().forEach( fs-> {
                ValueMonitor<Double> monitor = monitoredValues.<Double>create(FILE_STORE_SIZE_PREFIX + fs.getStoreName())
                        .name(new TranslatableMessage("internal.monitor.fileStoreSize", fs.getStoreDescription()))
                        .build();
                this.fileStoreMonitors.put(fs.getStoreName(), monitor);
            });
        } else {
            maHomeSize = null;
        }
    }

    @PostConstruct
    private void postConstruct() {
        this.scheduledFuture = scheduledExecutor.scheduleAtFixedRate(() -> {
            executor.execute(this::doPoll);
        }, 0, this.period, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    private void preDestroy() {
        ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
    }

    private void doPoll() {
        //Volume information for MA Home Partition
        try {
            FileStore store = Files.getFileStore(Common.MA_HOME_PATH);
            maHomePartitionTotalSpace.setValue(getGb(store.getTotalSpace()));
            maHomePartitionUsableSpace.setValue(getGb(store.getUsableSpace()));
            maHomePartitionUsedSpace.setValue(getGb(store.getTotalSpace() - store.getUsableSpace()));
        }catch(Exception e) {
            log.error("Unable to get MA_HOME partition usage", e);
        }
        //Volume information for NoSQL partition
        try {
            FileStore store = Files.getFileStore(Paths.get(NoSQLProxy.getDatabasePath()).toAbsolutePath());
            noSqlPartitionTotalSpace.setValue(getGb(store.getTotalSpace()));
            noSqlPartitionUsableSpace.setValue(getGb(store.getUsableSpace()));
            noSqlPartitionUsedSpace.setValue(getGb(store.getTotalSpace() - store.getUsableSpace()));
        }catch(Exception e) {
            log.error("Unable to get NoSQL partition usage", e);
        }

        //Volume information for sql db partition (if possible)
        File dataDirectory = Common.databaseProxy.getDataDirectory();
        if(dataDirectory != null) {
            try{
                FileStore store = Files.getFileStore(dataDirectory.toPath());
                sqlPartitionTotalSpace.setValue(getGb(store.getTotalSpace()));
                sqlPartitionUsableSpace.setValue(getGb(store.getUsableSpace()));
                sqlPartitionUsedSpace.setValue(getGb(store.getTotalSpace() - store.getUsableSpace()));
            }catch(Exception e) {
                log.error("Unable to get Filestore partition usage", e);
            }
        }

        //Volume information for filestore partition
        String location = Common.envProps.getString(FileStoreDefinition.FILE_STORE_LOCATION_ENV_PROPERTY);
        if (location == null || location.isEmpty()) {
            location = FileStoreDefinition.ROOT;
        }
        try{
            FileStore store = Files.getFileStore(Common.MA_HOME_PATH.resolve(location));
            filestorePartitionTotalSpace.setValue(getGb(store.getTotalSpace()));
            filestorePartitionUsableSpace.setValue(getGb(store.getUsableSpace()));
            filestorePartitionUsedSpace.setValue(getGb(store.getTotalSpace() - store.getUsableSpace()));
        }catch(Exception e) {
            log.error("Unable to get Filestore partition usage", e);
        }

        if (this.maHomeSize != null) {
            maHomeSize.setValue(getGb(getSize(Common.MA_HOME_PATH.toFile())));
        }

        long now = Common.timer.currentTimeMillis();
        this.sqlDatabaseSize.poll(now);
        this.noSqlDatabaseSize.poll(now);

        fileStoreMonitors.entrySet().stream().forEach(monitor -> {
            monitor.getValue().setValue(getGb(getSize(ModuleRegistry.getFileStoreDefinition(monitor.getKey()).getRoot())));
        });
    }

    /**
     * Get the size of a directory
     * @return
     */
    private long getSize(File directory) {
        if(!directory.exists()) {
            return -1l;
        }
        try {
            return FileUtils.sizeOfDirectory(directory);
        }catch(Exception e) {
            log.error("Unable to compute size of " + directory.getAbsolutePath(), e);
            return 0l;
        }
    }

    /**
     * Get the SQL database size, limit how often
     * @return
     */
    private Double getSqlDatabaseSizeMB() {
        long last = this.lastSqlDatabaseSizePollTime;
        long now = Common.timer.currentTimeMillis();
        if(now < last + MIN_SQL_DISK_SIZE_POLL_PERIOD) {
            return this.lastSqlDatabaseSize;
        }
        this.lastSqlDatabaseSizePollTime = now;
        this.lastSqlDatabaseSize = getGb(Common.databaseProxy.getDatabaseSizeInBytes());
        return this.lastSqlDatabaseSize;
    }

    /**
     * Get the NoSQ database size, limit how often
     * @return
     */
    private Double getNoSqlDatabaseSizeMB() {
        long last = this.lastNoSqlDatabaseSizePollTime;
        long now = Common.timer.currentTimeMillis();
        if(now < last + MIN_NOSQL_DISK_SIZE_POLL_PERIOD) {
            return this.lastNoSqlDatabaseSize;
        }
        this.lastNoSqlDatabaseSizePollTime = now;
        if (Common.databaseProxy.getNoSQLProxy() != null) {
            String pointDataStoreName = Common.envProps.getString("db.nosql.pointDataStoreName", "mangoTSDB");
            this.lastNoSqlDatabaseSize = getGb(Common.databaseProxy.getNoSQLProxy().getDatabaseSizeInBytes(pointDataStoreName));
        }else {
            this.lastNoSqlDatabaseSize = 0d;
        }
        return this.lastNoSqlDatabaseSize;
    }

    /**
     * Convert bytes to gigabytes
     * @param bytes
     * @return
     */
    private double getGb(long bytes) {
        return bytes/gb;
    }

}
