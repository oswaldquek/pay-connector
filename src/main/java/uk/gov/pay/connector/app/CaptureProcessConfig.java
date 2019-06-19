package uk.gov.pay.connector.app;

import io.dropwizard.Configuration;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

public class CaptureProcessConfig extends Configuration {

    private int chargesConsideredOverdueForCaptureAfter;
    private int maximumRetries;

    @Valid
    @NotNull
    private Boolean backgroundProcessingEnabled;

    private int failedCaptureRetryDelayInSeconds;
    private int queueSchedulerThreadDelayInSeconds;
    private int queueSchedulerNumberOfThreads;

    public int getChargesConsideredOverdueForCaptureAfter() {
        return chargesConsideredOverdueForCaptureAfter;
    }

    public int getMaximumRetries() {
        return maximumRetries;
    }

    public Boolean getBackgroundProcessingEnabled() { return backgroundProcessingEnabled; }

    public int getFailedCaptureRetryDelayInSeconds() {
        return failedCaptureRetryDelayInSeconds;
    }

    public int getQueueSchedulerThreadDelayInSeconds() {
        return queueSchedulerThreadDelayInSeconds;
    }

    public int getQueueSchedulerNumberOfThreads() {
        return queueSchedulerNumberOfThreads;
    }
}
