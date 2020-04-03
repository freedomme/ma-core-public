/**
 * Copyright (C) 2020  Infinite Automation Software. All rights reserved.
 */

package com.infiniteautomation.mango.spring.service;

import org.springframework.stereotype.Service;

import com.infiniteautomation.mango.util.exception.ValidationException;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.i18n.ProcessResult;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.CentralProcessor.LogicalProcessor;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

/**
 *
 * @author Terry Packer
 */
@Service
public class ServerInformationService {

    private final HardwareAbstractionLayer hal;
    private final OperatingSystem os;

    //Mango Process ID
    private final int pid;

    //Process CPU Load
    private long previousProcessTime = -1;
    private long lastProcessCpuPollTime;
    private Double lastProcessLoad;
    private static final long MIN_PROCESS_LOAD_POLL_PERIOD = 1000;

    //System CPU Load
    private long lastTicks[];
    private long lastSystemCpuPollTime;
    private Double lastSystemCpuLoad;
    private static final long MIN_CPU_LOAD_POLL_PERIOD = 1000;

    public ServerInformationService() {
        SystemInfo si = new SystemInfo();
        this.hal = si.getHardware();
        this.os = si.getOperatingSystem();
        this.pid = os.getProcessId();
    }

    /**
     * Get the Operating System
     * @return
     */
    public OperatingSystem getOperatingSystem() {
        return os;
    }

    /**
     * Get the hardware abstraction layer
     * @return
     */
    public HardwareAbstractionLayer getHardware() {
        return hal;
    }

    /**
     * Get Processor
     * @return
     */
    public CentralProcessor getProcessor() {
        return hal.getProcessor();
    }

    /**
     * Get the process running Mango
     * @return
     */
    public OSProcess getMangoProcess() {
        return os.getProcess(this.pid);
    }

    /**
     * Compute the CPU load of the Mango process,
     *  this limits to only allow an updated value every
     *  MIN_PROCESS_POLL_PERIOD ms to increase accuracy.
     *
     *  This is mildly thread safe in terms of accuracy, as
     *   it is possible to call this faster than desired
     *   but will only result is slightly less accurate readings
     *
     * @return
     */
    public Double processCpuLoad() {
        //https://github.com/oshi/oshi/issues/359
        long lpcpt = this.lastProcessCpuPollTime;
        long now = Common.timer.currentTimeMillis();
        if(now < lpcpt + MIN_PROCESS_LOAD_POLL_PERIOD) {
            return this.lastProcessLoad;
        }

        CentralProcessor processor = getProcessor();
        int cpuCount = processor.getLogicalProcessorCount();
        OSProcess p = getMangoProcess();

        if(this.lastProcessCpuPollTime == 0) {
            this.lastProcessCpuPollTime = now;
            return null;
        }

        long currentTime = p.getKernelTime() + p.getUserTime();
        long processCpuPollPeriod = now - this.lastProcessCpuPollTime;
        this.lastProcessCpuPollTime = now;

        if(previousProcessTime > 0 && processCpuPollPeriod > 0) {
            // If we have both a previous and a current time
            // we can calculate the CPU usage
            long timeDifference = currentTime - previousProcessTime;
            this.lastProcessLoad = (timeDifference / ((double) processCpuPollPeriod)) / cpuCount;
        }
        previousProcessTime = currentTime;
        return this.lastProcessLoad;
    }

    /**
     * Get the load average
     * @param increment - 1=1 minute, 2=5 minute, 3=15 minute
     * @return
     */
    public Double systemLoadAverage(int increment) throws ValidationException {
        if(increment < 1 || increment > 3) {
            ProcessResult result = new ProcessResult();
            result.addContextualMessage("increment", "validate.invalidValue");
        }

        CentralProcessor processor = getProcessor();
        double[] loadAverage = processor.getSystemLoadAverage(increment);

        return loadAverage[increment - 1];
    }

    /**
     * Returns the "recent cpu usage" for the whole system by counting ticks from a previous call
     *
     *  This is limited to only allow an updated value every
     *  MIN_CPU_LOAD_POLL_PERIOD ms to increase accuracy.
     *
     *  This is mildly thread safe in terms of accuracy, as
     *   it is possible to call this faster than desired
     *   but will only result is slightly less accurate readings
     */
    public Double systemCpuLoad() {
        long lpcpt = this.lastSystemCpuPollTime;
        long now = Common.timer.currentTimeMillis();
        if(now < lpcpt + MIN_CPU_LOAD_POLL_PERIOD) {
            return this.lastSystemCpuLoad;
        }

        CentralProcessor processor = getProcessor();

        if(this.lastSystemCpuPollTime == 0) {
            this.lastSystemCpuPollTime = now;
            return null;
        }

        if(this.lastTicks == null) {
            this.lastTicks = processor.getSystemCpuLoadTicks();
            this.lastSystemCpuPollTime = now;
            return null;
        }else {
            double load = processor.getSystemCpuLoadBetweenTicks(this.lastTicks);
            this.lastTicks = processor.getSystemCpuLoadTicks();
            this.lastSystemCpuPollTime = now;
            return load;
        }
    }

    /**
     * Get the logical processors of the machine, this will never return null
     *  but could be an empty array.
     * @return
     */
    public LogicalProcessor[] availableProcessors() {
        CentralProcessor processor = getProcessor();
        LogicalProcessor[] processors = processor.getLogicalProcessors();
        if(processors == null) {
            return new LogicalProcessor[0];
        }else {
            return processors;
        }
    }
}