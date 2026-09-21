package com.spaceagent.platform.integration.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="platform.project.coding")
public class ProjectCodingProperties {
    private boolean workerEnabled=true; private String workerId; private long pollDelayMs=1000;
    private int leaseSeconds=900; private int maximumAttempts=3;
    private int maximumIterations=20; private int maximumReviewRounds=2;
    public boolean isWorkerEnabled(){return workerEnabled;} public void setWorkerEnabled(boolean v){workerEnabled=v;}
    public String getWorkerId(){return workerId;} public void setWorkerId(String v){workerId=v;}
    public long getPollDelayMs(){return pollDelayMs;} public void setPollDelayMs(long v){pollDelayMs=v;}
    public int getLeaseSeconds(){return leaseSeconds;} public void setLeaseSeconds(int v){leaseSeconds=v;}
    public int getMaximumAttempts(){return maximumAttempts;} public void setMaximumAttempts(int v){maximumAttempts=v;}
    public int getMaximumIterations(){return maximumIterations;} public void setMaximumIterations(int v){maximumIterations=v;}
    public int getMaximumReviewRounds(){return maximumReviewRounds;} public void setMaximumReviewRounds(int v){maximumReviewRounds=v;}
}
