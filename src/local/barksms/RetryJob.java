package local.barksms;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

public final class RetryJob extends JobService {
    private static final int JOB_ID = 17301;

    static void schedule(Context context, long delay) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(context, RetryJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(Math.max(1000, delay))
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true).build();
        if (scheduler.schedule(info) == JobScheduler.RESULT_FAILURE) {
            QueueDb.get(context).event("系统重试任务未获准");
        }
    }

    static void cancel(Context context) { context.getSystemService(JobScheduler.class).cancel(JOB_ID); }

    @Override public boolean onStartJob(JobParameters params) {
        if (new Settings(this).enabled() && Settings.hasPermission(this)) ForwardService.start(this, false);
        return false;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
